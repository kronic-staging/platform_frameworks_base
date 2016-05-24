/*
 *  Copyright (C) 2015 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.omni;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.util.ArrayMap;
import android.util.Log;
import android.os.UserHandle;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.android.systemui.R;
import com.android.internal.util.omni.PackageUtils;

import android.provider.Settings;

public class DaylightHeaderProvider implements
        StatusBarHeaderMachine.IStatusBarHeaderProvider {

    public static final String TAG = "DaylightHeaderProvider";
    private static final boolean DEBUG = false;
    // how many header drawables we currently cache
    private static final int HEADER_COUNT = 9;
    // Due to the nature of CMTE SystemUI multiple overlays, we are now required
    // to hard reference our themed drawables
    private ArrayMap<Integer, Drawable> mCache = new ArrayMap<Integer, Drawable>(HEADER_COUNT);
    private boolean mThemeswitch;

    private class DaylightHeaderInfo {
        public int mType = 0;
        public int mHour = -1;
        public int mDay = -1;
        public int mMonth = -1;
        public String mImage;
    }
    // default in SystemUI
    private static final String HEADER_PACKAGE_DEFAULT = "com.android.systemui";

    // Default drawable (AOSP)
    private static final int DRAWABLE_DEFAULT = R.drawable.notification_header_bg;

    private Context mContext;
    private List<DaylightHeaderInfo> mHeadersList;
    private Resources mRes;
    private String mPackageName;
    private String mHeaderName;
    private String mSettingHeaderPackage;
    private PendingIntent mAlarmHourly;
    private boolean mRandomMode;
    private int mRandomIndex;
    
    // Daily calendar periods
    private static final int TIME_SUNRISE = 6;
    private static final int DRAWABLE_SUNRISE = R.drawable.notifhead_sunrise;
    private static final int TIME_MORNING = 9;
    private static final int DRAWABLE_MORNING = R.drawable.notifhead_morning;
    private static final int TIME_NOON = 11;
    private static final int DRAWABLE_NOON = R.drawable.notifhead_noon;
    private static final int TIME_AFTERNOON = 13;
    private static final int DRAWABLE_AFTERNOON = R.drawable.notifhead_afternoon;
    private static final int TIME_SUNSET = 19;
    private static final int DRAWABLE_SUNSET = R.drawable.notifhead_sunset;
    private static final int TIME_NIGHT = 21;
    private static final int DRAWABLE_NIGHT = R.drawable.notifhead_night;   
    // Special events
    // Christmas is on Dec 25th
    private static final Calendar CAL_CHRISTMAS = Calendar.getInstance();
    private static final int DRAWABLE_CHRISTMAS = R.drawable.notifhead_christmas;
    // New years eve is on Dec 31st
    private static final Calendar CAL_NEWYEARSEVE = Calendar.getInstance();
    private static final int DRAWABLE_NEWYEARSEVE = R.drawable.notifhead_newyearseve;

    public DaylightHeaderProvider(Context context,Resources res) {
	mContext = context;
	updateResources(res);
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;

	mThemeswitch = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.THEME_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        if (customHeader) {
            if (mThemeswitch) {
	    getsettings(); 
	    settingsChanged();
	    } else {
	    settingsChanged();
            }
          }  
    }
    
    @Override
    public void updateResources(Resources res) {
        mCache.clear();
        mCache.put(DRAWABLE_SUNRISE, res.getDrawable(DRAWABLE_SUNRISE));
        mCache.put(DRAWABLE_MORNING, res.getDrawable(DRAWABLE_MORNING));
        mCache.put(DRAWABLE_NOON, res.getDrawable(DRAWABLE_NOON));
        mCache.put(DRAWABLE_AFTERNOON, res.getDrawable(DRAWABLE_AFTERNOON));
        mCache.put(DRAWABLE_SUNSET, res.getDrawable(DRAWABLE_SUNSET));
        mCache.put(DRAWABLE_NIGHT, res.getDrawable(DRAWABLE_NIGHT));
        mCache.put(DRAWABLE_CHRISTMAS, res.getDrawable(DRAWABLE_CHRISTMAS));
        mCache.put(DRAWABLE_NEWYEARSEVE, res.getDrawable(DRAWABLE_NEWYEARSEVE));
        mCache.put(DRAWABLE_DEFAULT, res.getDrawable(DRAWABLE_DEFAULT));
     }

    @Override
    public String getName() {
        return TAG;
    }
    
    public void getsettings() {
        // There is one downside with this method: it will only work once a
        // year,
        // if you don't reboot your phone. I hope you will reboot your phone
        // once
        // in a year.
        CAL_CHRISTMAS.set(Calendar.MONTH, Calendar.DECEMBER);
        CAL_CHRISTMAS.set(Calendar.DAY_OF_MONTH, 25);

        CAL_NEWYEARSEVE.set(Calendar.MONTH, Calendar.DECEMBER);
        CAL_NEWYEARSEVE.set(Calendar.DAY_OF_MONTH, 31);  
    }

    @Override
    public void settingsChanged() {
        final String settingHeaderPackage = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK,
                UserHandle.USER_CURRENT);

        mThemeswitch = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.THEME_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        if (settingHeaderPackage == null) {
            loadDefaultHeaderPackage();
        } else if (mSettingHeaderPackage == null || !settingHeaderPackage.equals(mSettingHeaderPackage)) {
            mSettingHeaderPackage = settingHeaderPackage;
            loadCustomHeaderPackage();
        }
    }

    @Override
    public void enableProvider() {
        startAlarm();
    }

    @Override
    public void disableProvider() {
        stopAlarm();
   }

    private void stopAlarm() {
        if (mAlarmHourly != null) {
            final AlarmManager alarmManager = (AlarmManager) mContext
                    .getSystemService(Context.ALARM_SERVICE);
            if (DEBUG) Log.i(TAG, "stop hourly alarm");
            alarmManager.cancel(mAlarmHourly);
        }
        mAlarmHourly = null;
    }

    private void startAlarm() {
        // TODO actually this should find out the next needed alarm
        // instead of forcing it every hour
        final Calendar c = Calendar.getInstance();
        final AlarmManager alarmManager = (AlarmManager) mContext
                .getSystemService(Context.ALARM_SERVICE);

        if (mAlarmHourly != null) {
            alarmManager.cancel(mAlarmHourly);
        }
        Intent intent = new Intent(StatusBarHeaderMachine.STATUS_BAR_HEADER_UPDATE_ACTION);
        mAlarmHourly = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        // make sure hourly alarm is aligned with hour
        c.add(Calendar.HOUR_OF_DAY, 1);
        c.set(Calendar.MINUTE, 0);
        long hourlyStart = c.getTimeInMillis();
        if (DEBUG) Log.i(TAG, "start hourly alarm with " + new Date(hourlyStart));
        alarmManager.setInexactRepeating(AlarmManager.RTC, hourlyStart,
                AlarmManager.INTERVAL_HOUR, mAlarmHourly);
    }

    private void loadCustomHeaderPackage() {
        if (DEBUG) Log.i(TAG, "Load header pack " + mSettingHeaderPackage);
        int idx = mSettingHeaderPackage.indexOf("/");
        if (idx != -1) {
            String[] parts = mSettingHeaderPackage.split("/");
            mPackageName = parts[0];
            mHeaderName = parts[1];
        } else {
            mPackageName = mSettingHeaderPackage;
            mHeaderName = null;
        }
        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mPackageName);
            loadHeaders();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load icon pack " + mHeaderName, e);
            mRes = null;
        }
        if (mRes == null) {
            Log.w(TAG, "Header pack loading failed - loading default");
            loadDefaultHeaderPackage();
        }
    }

    private void loadDefaultHeaderPackage() {
        if (DEBUG) Log.i(TAG, "Load default header pack");
        mPackageName = HEADER_PACKAGE_DEFAULT;
        mHeaderName = null;
        mSettingHeaderPackage = mPackageName;
        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mPackageName);
            loadHeaders();
        } catch (Exception e) {
            mRes = null;
        }
        if (mRes == null) {
            Log.w(TAG, "No default package found");
        }
    }

    private void loadHeaders() throws XmlPullParserException, IOException {
        mHeadersList = new ArrayList<DaylightHeaderInfo>();
        InputStream in = null;
        XmlPullParser parser = null;

        try {
            if (mHeaderName == null) {
                if (DEBUG) Log.i(TAG, "Load header pack config daylight_header.xml");
                in = mRes.getAssets().open("daylight_header.xml");
            } else {
                int idx = mHeaderName.lastIndexOf(".");
                String headerConfigFile = mHeaderName.substring(idx + 1) + ".xml";
                if (DEBUG) Log.i(TAG, "Load header pack config " + headerConfigFile);
                in = mRes.getAssets().open(headerConfigFile);
            }
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            parser = factory.newPullParser();
            parser.setInput(in, "UTF-8");
            loadResourcesFromXmlParser(parser);
        } finally {
            // Cleanup resources
            if (parser instanceof XmlResourceParser) {
                ((XmlResourceParser) parser).close();
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void loadResourcesFromXmlParser(XmlPullParser parser) throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        mRandomMode = false;
        do {
            if (eventType != XmlPullParser.START_TAG) {
                continue;
            }
            // TODO support different hours for day headers
            if (parser.getName().equalsIgnoreCase("day_header")) {
                if (mRandomMode) {
                    continue;
                }
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                headerInfo.mType = 0;
                String day = parser.getAttributeValue(null, "day");
                if (day != null) {
                    headerInfo.mDay = Integer.valueOf(day);
                }
                String month = parser.getAttributeValue(null, "month");
                if (month != null) {
                    headerInfo.mMonth = Integer.valueOf(month);
                }
                String image = parser.getAttributeValue(null, "image");
                if (image != null) {
                    headerInfo.mImage = image;
                }
                if (headerInfo.mImage != null && headerInfo.mDay != -1 && headerInfo.mMonth != -1) {
                    mHeadersList.add(headerInfo);
                }
            } else if (parser.getName().equalsIgnoreCase("hour_header")) {
                if (mRandomMode) {
                    continue;
                }
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                headerInfo.mType = 1;
                String hour = parser.getAttributeValue(null, "hour");
                if (hour != null) {
                    headerInfo.mHour = Integer.valueOf(hour);
                }
                String image = parser.getAttributeValue(null, "image");
                if (image != null) {
                    headerInfo.mImage = image;
                }
                if (headerInfo.mImage != null && headerInfo.mHour != -1) {
                    mHeadersList.add(headerInfo);
                }
            } else if (parser.getName().equalsIgnoreCase("random_header")) {
                if (!mRandomMode) {
                    if (DEBUG) Log.i(TAG, "Load random mode header pack");
                    mRandomMode = true;
                    mHeadersList.clear();
                }
                DaylightHeaderInfo headerInfo = new DaylightHeaderInfo();
                headerInfo.mType = 2;
                String image = parser.getAttributeValue(null, "image");
                if (image != null) {
                    headerInfo.mImage = image;
                }
                if (headerInfo.mImage != null) {
                    mHeadersList.add(headerInfo);
                }
            }
        } while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT);

        if (mRandomMode) {
            Collections.shuffle(mHeadersList);
        }
    }

    /**
     * hour header with biggest hour
     */
    private DaylightHeaderInfo getLastHourHeader() {
        if (mHeadersList == null || mHeadersList.size() == 0) {
            return null;
        }
        Iterator<DaylightHeaderInfo> nextHeader = mHeadersList.iterator();
        int hour = -1;
        DaylightHeaderInfo last = null;
        while(nextHeader.hasNext()) {
            DaylightHeaderInfo header = nextHeader.next();
            if (header.mType == 1) {
                if (last == null) {
                    last = header;
                    hour = last.mHour;
                } else if (header.mHour > hour) {
                    last = header;
                    hour = last.mHour;
                }
            }
        }
        return last;
    }

    /**
     * hour header with lowest hour
     */
    private DaylightHeaderInfo getFirstHourHeader() {
        if (mHeadersList == null || mHeadersList.size() == 0) {
            return null;
        }
        Iterator<DaylightHeaderInfo> nextHeader = mHeadersList.iterator();
        DaylightHeaderInfo first = null;
        int hour = -1;
        while(nextHeader.hasNext()) {
            DaylightHeaderInfo header = nextHeader.next();
            if (header.mType == 1) {
                if (first == null) {
                    first = header;
                    hour = first.mHour;
                } else if (header.mHour < hour) {
                    first = header;
                    hour = first.mHour;
                }
            }
        }
        return first;
    }

    @Override
    public Drawable getCurrent(final Calendar now) {
    boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
    mThemeswitch = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.THEME_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        if (mThemeswitch) {        
	  if (now == null) {
            return loadOrFetch(DRAWABLE_DEFAULT);
	  } 
        // Check special events first. They have the priority over any other
        // period.
        if (isItTodaytoo(CAL_CHRISTMAS)) {
            // Merry christmas!
            return loadOrFetch(DRAWABLE_CHRISTMAS);
        } else if (isItTodaytoo(CAL_NEWYEARSEVE)) {
            // Happy new year!
            return loadOrFetch(DRAWABLE_NEWYEARSEVE);
        }

        // Now we check normal periods
        final int hour = now.get(Calendar.HOUR_OF_DAY);

        if (hour < TIME_SUNRISE || hour >= TIME_NIGHT) {
            return loadOrFetch(DRAWABLE_NIGHT);
        } else if (hour >= TIME_SUNRISE && hour < TIME_MORNING) {
            return loadOrFetch(DRAWABLE_SUNRISE);
        } else if (hour >= TIME_MORNING && hour < TIME_NOON) {
            return loadOrFetch(DRAWABLE_MORNING);
        } else if (hour >= TIME_NOON && hour < TIME_AFTERNOON) {
            return loadOrFetch(DRAWABLE_NOON);
        } else if (hour >= TIME_AFTERNOON && hour < TIME_SUNSET) {
            return loadOrFetch(DRAWABLE_AFTERNOON);
        } else if (hour >= TIME_SUNSET && hour < TIME_NIGHT) {
            return loadOrFetch(DRAWABLE_SUNSET);
        }

        // When all else fails, just be yourself
        Log.w(TAG, "No drawable for status  bar when it is " + hour + "!");
	  } else {
        if (mRes == null || mHeadersList == null || mHeadersList.size() == 0) {
            return null;
        }

        if (!PackageUtils.isAvailableApp(mPackageName, mContext)) {
            Log.w(TAG, "Header pack no longer available - loading default " + mPackageName);
            loadDefaultHeaderPackage();
        }
        try {
            if (mRandomMode) {
                if (mHeadersList.size() == 0) {
                    return null;
                }
                DaylightHeaderInfo header = mHeadersList.get(mRandomIndex);
                mRandomIndex++;
                if (mRandomIndex == mHeadersList.size()) {
                    if (DEBUG) Log.i(TAG, "Shuffle random mode header pack");
                    Collections.shuffle(mHeadersList);
                    mRandomIndex = 0;
                }
                return mRes.getDrawable(mRes.getIdentifier(header.mImage, "drawable", mPackageName), null);
            }
            // first check day headers
            Iterator<DaylightHeaderInfo> nextHeader = mHeadersList.iterator();
            while(nextHeader.hasNext()) {
                // first check day entries - they overrule hour entries
                DaylightHeaderInfo header = nextHeader.next();
                if (header.mType == 0) {
                    if (isItToday(now, header)){
                        return mRes.getDrawable(mRes.getIdentifier(header.mImage, "drawable", mPackageName), null);
                    }
                }
            }
            DaylightHeaderInfo first = getFirstHourHeader();
            DaylightHeaderInfo last = getLastHourHeader();
            DaylightHeaderInfo prev = first;

            nextHeader = mHeadersList.iterator();
            while(nextHeader.hasNext()) {
                DaylightHeaderInfo header = nextHeader.next();
                if (header.mType == 1) {
                    final int hour = now.get(Calendar.HOUR_OF_DAY);
                    if (header.mHour > hour) {
                        if (header == first) {
                            // if before first return last
                            return mRes.getDrawable(mRes.getIdentifier(last.mImage, "drawable", mPackageName), null);
                        }
                        // on the first bigger one return prev
                        return mRes.getDrawable(mRes.getIdentifier(prev.mImage, "drawable", mPackageName), null);
                    }
                    prev = header;
                }
            }
            return mRes.getDrawable(mRes.getIdentifier(last.mImage, "drawable", mPackageName), null);
        } catch(Resources.NotFoundException e) {
            Log.w(TAG, "No drawable found for " + now +" in " + mPackageName);
        } 
     }  
     return null;
    }

    private boolean isItToday(final Calendar now, DaylightHeaderInfo headerInfo) {
        return now.get(Calendar.MONTH) +1 == headerInfo.mMonth && now
                    .get(Calendar.DAY_OF_MONTH) == headerInfo.mDay;
    }
    
    private Drawable loadOrFetch(int resId) {
    return mCache.get(resId);
    }
    
    private static boolean isItTodaytoo(final Calendar date) {
        final Calendar now = Calendar.getInstance();
        return (now.get(Calendar.MONTH) == date.get(Calendar.MONTH) && now
                .get(Calendar.DAY_OF_MONTH) == date.get(Calendar.DAY_OF_MONTH));
    }
}

