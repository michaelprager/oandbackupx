package dk.jens.backup;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import java.util.Locale;

public class LanguageHelper
{
    // no need to change anything on start if default is choosen
    public void initLanguage(Context context, String langCode)
    {
        if(!langCode.equals("system"))
        {
            changeLanguage(context, langCode);
        }
    }
    public boolean changeLanguage(Context context, String langCode)
    {
        if(!langCode.equals("system"))
        {
            Resources res = context.getResources();
            Configuration conf = res.getConfiguration();
            String lang = conf.locale.getLanguage();
            conf.locale = new Locale(langCode);
            res.updateConfiguration(conf, res.getDisplayMetrics());
            // return true if language changed
            return !lang.equals(langCode);
        }
        else
        {
            return changeLanguage(context, new Locale(langCode).getDefault().getLanguage());
        }
    }
}