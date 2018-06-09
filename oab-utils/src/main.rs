extern crate clap;
extern crate libc;

use std::fmt;
use std::error::Error;
use std::fs;
use std::os::unix::fs::MetadataExt;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use clap::{Arg, App, AppSettings, SubCommand};

// https://blog.rust-lang.org/2016/05/13/rustup.html
// https://users.rust-lang.org/t/announcing-cargo-apk/5501

#[derive(Debug, Clone)]
pub struct OabError {
    pub message: String
}

impl fmt::Display for OabError {
    fn fmt(&self, f: &mut fmt::Formatter) -> Result<(), fmt::Error> {
        write!(f, "{}", self.message)
    }
}

impl std::error::Error for OabError {
    fn description(&self) -> &str {
        &self.message
    }
}

fn get_owner_ids(path: &str) -> Result<(u32, u32), std::io::Error> {
    let metadata = fs::metadata(Path::new(path))?;
    Ok((metadata.uid(), metadata.gid()))
}

fn str_to_cstring(s: &str) -> Result<std::ffi::CString, OabError> {
    match std::ffi::CString::new(s) {
        Ok(s) => Ok(s),
        Err(e) => {
            return Err(OabError {
                    message: e.description().to_string()
                }
            )
        }
    }
}

fn parse_id_input(id: &str) -> Result<(u32, Option<u32>), OabError> {
    if let Some(index) = id.find(":") {
        let uid_str = &id[..index];
        let uid = match uid_str.parse::<u32>() {
            Ok(uid) => uid,
            Err(_) => get_uid(uid_str)?
        };
        let gid_str = &id[index + 1..];
        let gid = match gid_str.parse::<u32>() {
            Ok(gid) => gid,
            Err(_) => get_gid(gid_str)?
        };
        return Ok((uid, Some(gid)));
    }
    let uid = match id.parse::<u32>() {
        Ok(uid) => uid,
        Err(_e) => get_uid(id)?
    };
    Ok((uid, None))
}

fn get_uid(name: &str) -> Result<u32, OabError> {
    unsafe {
        let name_cstring = str_to_cstring(name)?;
        let mut buf = Vec::with_capacity(512);
        let mut passwd: libc::passwd = std::mem::zeroed();
        let mut result = std::ptr::null_mut();
        match libc::getpwnam_r(name_cstring.as_ptr(), &mut passwd,
                buf.as_mut_ptr(), buf.capacity(), &mut result) {
            0 if !result.is_null() => Ok((*result).pw_uid),
            _ => Err(OabError {
                    message: format!("no uid found for user {}", name)
                }
            )
        }
    }
}

fn get_gid(name: &str) -> Result<u32, OabError> {
    unsafe {
        let name_cstring = str_to_cstring(name)?;
        // armv7-linux-androideabi doesn't have getgrnam_r so we have to
        // use getgrnam instead
        let group = libc::getgrnam(name_cstring.as_ptr());
        match group.as_ref() {
            Some(group) => Ok((*group).gr_gid),
            None => Err(OabError {
                    message: format!("no gid found for group {}", name)
                }
            )
        }
    }
}

fn change_owner(path: &str, uid: u32, gid: u32) -> Result<(), OabError> {
    let path_cstring = str_to_cstring(path)?;
    unsafe {
        match libc::chown(path_cstring.as_ptr(), uid, gid) {
            0 => Ok(()),
            _ => Err(OabError {
                    message: format!("unable to change owner of {} to {}:{}", path,
                        uid, gid)
                }
            )
        }
    }
}

fn set_permissions(p: &str, mode: u32) -> bool {
    let path = Path::new(p);
    let mut perms = match fs::metadata(path) {
        Err(e) => {
            eprintln!("error getting permissions for {:?}: {}", path,
                e.description());
            return false
        }
        Ok(metadata) => metadata.permissions()
    };
    if perms.mode() & 0o777 != mode {
        perms.set_mode(mode);
        match std::fs::set_permissions(path, perms) {
                Err(e) => {
                    eprintln!("unable to set mode {:o} on path {:?}: {}",
                        mode, path, e.description());
                    return false;
                }
                Ok(_) => return true
        };
    }
    false
}

fn main() {
    // https://github.com/kbknapp/clap-rs
    let args = App::new("oab-utils")
        .setting(AppSettings::ArgRequiredElseHelp)
        .subcommand(SubCommand::with_name("owner")
            .arg(Arg::with_name("input")
                .help("file to get info from")
                .required(true)))
        .subcommand(SubCommand::with_name("set-permissions")
            .arg(Arg::with_name("mode")
                .help("mode to set, in octal (e.g. 644)")
                .required(true))
            .arg(Arg::with_name("input")
                .required(true))
        )
        .subcommand(SubCommand::with_name("change-owner")
            .arg(Arg::with_name("id")
                .help("uid and optionally gid to set, separated by :")
                .required(true))
            .arg(Arg::with_name("path")
                .required(true))
        )
        .get_matches();
    match args.subcommand() {
        ("owner", Some(args)) => {
            let input = args.value_of("input").unwrap();
            match get_owner_ids(input) {
                Ok((uid, gid)) => {
                    println!("uid: {}\ngid: {}", uid, gid);
                },
                Err(e) => {
                    eprintln!("error getting owner ids for {}: {}", input,
                        e.description());
                    std::process::exit(1);
                }
            };
        },
        ("set-permissions", Some(args)) => {
            let input = args.value_of("input").unwrap();
            let mode_str = args.value_of("mode").unwrap();
            let mode = match u32::from_str_radix(mode_str, 8) {
                Err(e) => {
                    eprintln!("error parsing input {}: {}", mode_str, e.description());
                    std::process::exit(1);
                }
                Ok(value) => value
            };
            if !set_permissions(input, mode) {
                std::process::exit(1);
            };
        },
        ("change-owner", Some(args)) => {
            let path = args.value_of("path").unwrap();
            match parse_id_input(args.value_of("id").unwrap()) {
                Ok((uid, gid_option)) => {
                    let gid = match gid_option {
                        Some(gid) => gid,
                        None => match get_owner_ids(path) {
                            Ok((_, gid)) => gid,
                            Err(e) => {
                                eprintln!("unable to get group id for {}: {}",
                                    path, e.description());
                                std::process::exit(1);
                            }
                        }
                    };
                    if let Err(e) = change_owner(path, uid, gid) {
                        eprintln!("{}", e.description());
                        std::process::exit(1);
                    };
                },
                Err(e) => eprintln!("unable to parse input: {}", e.description())
            };
        },
        ("", None) => {
            eprintln!("no commands specified");
            std::process::exit(1);
        },
        _ => unreachable!()
    }
}

#[cfg(test)]
mod test_main;