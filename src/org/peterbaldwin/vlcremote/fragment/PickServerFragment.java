/*-
 *  Copyright (C) 2009 Peter Baldwin   
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.peterbaldwin.vlcremote.fragment;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ListAdapter;
import java.net.HttpURLConnection;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import org.peterbaldwin.client.android.vlcremote.R;
import org.peterbaldwin.vlcremote.app.PickServerActivity;
import org.peterbaldwin.vlcremote.model.Preferences;
import org.peterbaldwin.vlcremote.model.Server;
import org.peterbaldwin.vlcremote.preference.ProgressCategory;
import org.peterbaldwin.vlcremote.receiver.PhoneStateChangedReceiver;
import org.peterbaldwin.vlcremote.sweep.PortSweeper;

public final class PickServerFragment extends PreferenceFragment implements PortSweeper.Callback,
        ServerInfoDialog.ServerInfoDialogListener, OnPreferenceChangeListener {

    private static final String TAG = "PickServer";

    private static final String PACKAGE_NAME = R.class.getPackage().getName();
    
    private static final ComponentName PHONE_STATE_RECEIVER = new ComponentName(PACKAGE_NAME,
            PhoneStateChangedReceiver.class.getName());

    private static final String KEY_WIFI = "wifi";
    private static final String KEY_PARSE_PLAYLIST_ITEMS = "parse_playlist_items";
    private static final String KEY_SORT_DIRECTORIES_FIRST = "sort_directories_first";
    private static final String KEY_HIDE_DVD_TAB = "hide_dvd_tab";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_ADD_SERVER = "add_server";
    private static final String KEY_PAUSE_FOR_CALL = "pause_for_call";
    private static final String KEY_SEEK_TIME = "seek_time";
    
    public static final String STATE_HOSTS = "hosts";

    private static final String DIALOG_ADD_SERVER = "add_server";
    private static final String DIALOG_EDIT_SERVER = "edit_server";

    private static final int CONTEXT_FORGET = Menu.FIRST;
    private static final int CONTEXT_EDIT_SERVER = 2;

    private BroadcastReceiver mReceiver;
    
    private int mPort;

    private ArrayList<String> mRemembered;

    private EditTextPreference mPreferenceSeekTime;
    private CheckBoxPreference mPreferenceWiFi;
    private CheckBoxPreference mPreferencePauseForCall;
    private CheckBoxPreference mPreferenceParsePlaylistItems;
    private CheckBoxPreference mPreferenceSortDirectoriesFirst;
    private CheckBoxPreference mPreferenceHideDVDTab;
    private ProgressCategory mProgressCategory;
    private Preference mPreferenceAddServer;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        ((PickServerActivity) activity).setServerInfoDialogListener(this);
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.server_settings);
        
        PreferenceScreen preferenceScreen = getPreferenceScreen();

        mPreferenceWiFi = (CheckBoxPreference) preferenceScreen.findPreference(KEY_WIFI);
        mPreferencePauseForCall = (CheckBoxPreference) preferenceScreen.findPreference(KEY_PAUSE_FOR_CALL);
        mPreferenceParsePlaylistItems = (CheckBoxPreference) preferenceScreen.findPreference(KEY_PARSE_PLAYLIST_ITEMS);
        mPreferenceSortDirectoriesFirst = (CheckBoxPreference) preferenceScreen.findPreference(KEY_SORT_DIRECTORIES_FIRST);
        mPreferenceHideDVDTab = (CheckBoxPreference) preferenceScreen.findPreference(KEY_HIDE_DVD_TAB);
        mPreferenceSeekTime = (EditTextPreference) preferenceScreen.findPreference(KEY_SEEK_TIME);
        mPreferenceSeekTime.setOnPreferenceChangeListener(this);
        mProgressCategory = (ProgressCategory) preferenceScreen.findPreference(KEY_SERVERS);
        mPreferenceAddServer = preferenceScreen.findPreference(KEY_ADD_SERVER);
        
        mPreferencePauseForCall.setOnPreferenceChangeListener(this);
        mPreferencePauseForCall.setChecked(getPauseForCall());

        Intent intent = getActivity().getIntent();
        mPort = intent.getIntExtra(PortSweeper.EXTRA_PORT, 0);
        if (mPort == 0) {
            throw new IllegalArgumentException("Port must be specified");
        }
        
        mRemembered = intent.getStringArrayListExtra(PortSweeper.EXTRA_REMEMBERED);
        if (mRemembered == null) {
            mRemembered = new ArrayList<String>();
        }
        
        mReceiver = new MyBroadcastReceiver();
        updateWifiInfo();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = super.onCreateView(inflater, container, savedInstanceState);
        registerForContextMenu(v.findViewById(android.R.id.list));
        return v;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if(savedInstanceState != null) {
            List<String> hosts = savedInstanceState.getStringArrayList(STATE_HOSTS);
            for (int i = 0; i < hosts.size(); i++) {
                Preference preference = createServerPreference(Server.fromKey(hosts.get(i)));
                mProgressCategory.addPreference(preference);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        getActivity().registerReceiver(mReceiver, filter);
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().unregisterReceiver(mReceiver);
    }
    
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        int n = mProgressCategory.getPreferenceCount();
        ArrayList<String> hosts = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) {
            Preference preference = mProgressCategory.getPreference(i);
            String authority = Server.fromKey(preference.getKey()).getUri().getAuthority();
            if (!mRemembered.contains(authority)) {
                hosts.add(preference.getKey());
            }
        }
        outState.putStringArrayList(STATE_HOSTS, hosts);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        ((PickServerActivity) getActivity()).setServerInfoDialogListener(null);
    }
    
    private Preference createServerPreference(Server server) {
        Preference preference = new Preference(getActivity());
        preference.setKey(server.toKey());
        preference.setTitle(server.getHostAndPort());
        preference.setPersistent(false);
        String authority = server.getUri().getAuthority();
        if(mRemembered.contains(authority) && authority.equals(Preferences.get(getActivity()).getAuthority())) {
            preference.setWidgetLayoutResource(R.layout.tick_image);
        }
        switch (server.getResponseCode()) {
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                preference.setSummary(R.string.summary_unauthorized);
                preference.setIcon(R.drawable.ic_vlc_server_auth);
                break;
            case HttpURLConnection.HTTP_FORBIDDEN:
                preference.setSummary(R.string.summary_forbidden);
                preference.setIcon(R.drawable.ic_vlc_server_forbidden);
                break;
            default:
                if(server.hasUserInfo()) {
                    preference.setIcon(R.drawable.ic_vlc_server_auth);
                } else {
                    preference.setIcon(R.drawable.ic_vlc_server);
                }
        }
        return preference;
    }

    private WifiInfo getConnectionInfo() {
        WifiManager manager = (WifiManager) getActivity().getSystemService(Activity.WIFI_SERVICE);
        WifiInfo info = manager.getConnectionInfo();
        if (info != null) {
            SupplicantState state = info.getSupplicantState();
            if (state.equals(SupplicantState.COMPLETED)) {
                return info;
            }
        }
        return null;
    }

    @Override
    public void onProgress(int progress, int max) {
        if(getActivity() == null) {
            return;
        }
        if (progress == 0) {
            mProgressCategory.removeAll();
            for (String authority : mRemembered) {
                Preference preference = createServerPreference(new Server(authority));
                preference.setSummary(R.string.summary_remembered);
                mProgressCategory.addPreference(preference);
            }
        }
        mProgressCategory.setProgress(progress != max);
    }

    @Override
    public void onHostFound(String hostname, int responseCode) {
        Server server = new Server(hostname + ":" + mPort, responseCode);
        switch (responseCode) {
            case HttpURLConnection.HTTP_OK:
            case HttpURLConnection.HTTP_FORBIDDEN:
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                if (!mRemembered.contains(server.getUri().getAuthority())) {
                    Preference preference = createServerPreference(server);
                    mProgressCategory.addPreference(preference);
                }
                break;
            default:
                Log.d(TAG, "Unexpected response code: " + responseCode);
                break;
        }
    }
    
    @Override
    public void onAddServer(Server server) {
        Intent data = new Intent();
        data.setData(server.getUri());
        if (!mRemembered.contains(server.getUri().getAuthority())) {
            mRemembered.add(server.getUri().getAuthority());
        }
        data.putStringArrayListExtra(PortSweeper.EXTRA_REMEMBERED, mRemembered);
        getActivity().setResult(Activity.RESULT_OK, data);
        getActivity().finish();
    }
    
    @Override
    public void onEditServer(Server newServer, String oldServerKey) {
        String oldAuthority = Server.fromKey(oldServerKey).getUri().getAuthority();
        for(int i = 0; i < mRemembered.size(); i++) {
            if(mRemembered.get(i).equals(oldAuthority)) {
                mRemembered.set(i, newServer.getUri().getAuthority());
                break;
            }
        }
        Preferences.get(getActivity()).setRemeberedServers(mRemembered);
    }
    
    private void forget(Server server) {
        mRemembered.remove(server.getUri().getAuthority());
        int count = mProgressCategory.getPreferenceCount();
        for (int i = 0; i < count; i++) {
            Preference preference = mProgressCategory.getPreference(i);
            if (server.toKey().equals(preference.getKey())) {
                mProgressCategory.removePreference(preference);
                break;
            }
        }

        // Send the updated list of remembered servers even if the activity is
        // canceled
        Intent data = new Intent();
        data.putStringArrayListExtra(PortSweeper.EXTRA_REMEMBERED, mRemembered);
        getActivity().setResult(Activity.RESULT_CANCELED, data);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mPreferenceAddServer) {
            ServerInfoDialog.addServerInstance().show(getFragmentManager(), DIALOG_ADD_SERVER);
            return true;
        } else if (preference == mPreferenceWiFi) {
            Intent intent = new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK);
            startActivity(intent);

            // Undo checkbox toggle
            updateWifiInfo();
            return true;
        } else if (preference == mPreferencePauseForCall) {
            return super.onPreferenceTreeClick(preferenceScreen, preference);
        } else if (preference == mPreferenceParsePlaylistItems) {
            Preferences.get(getActivity()).setParsePlaylistItems(mPreferenceParsePlaylistItems.isChecked());
            return true;
        } else if (preference == mPreferenceHideDVDTab) {
            Preferences.get(getActivity()).setHideDVDTab(mPreferenceHideDVDTab.isChecked());
            return true;
        } else if (preference == mPreferenceSortDirectoriesFirst) {
            Preferences.get(getActivity()).setSortDirectoriesFirst(mPreferenceSortDirectoriesFirst.isChecked());
            return true;
        } else if (preference == mPreferenceSeekTime) {
            return super.onPreferenceTreeClick(preferenceScreen, preference);
        } else {
            Server server = Server.fromKey(preference.getKey());
            if(server.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                ServerInfoDialog.addAuthServerInstance(server.toKey()).show(getFragmentManager(), DIALOG_ADD_SERVER);
            } else {
                onAddServer(server);
            }
            return true;
        }
    }
    
    /** {@inheritDoc} */
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mPreferencePauseForCall) {
            setPauseForCall(Boolean.TRUE.equals(newValue));
            return true;
        } else if(preference == mPreferenceSeekTime) {
            Preferences.get(getActivity()).setSeekTime((String) newValue);
            return true;
        }
        return false;
    }

    private Preference getPreferenceFromMenuInfo(ContextMenuInfo menuInfo) {
        if (menuInfo != null) {
            if (menuInfo instanceof AdapterContextMenuInfo) {
                AdapterContextMenuInfo adapterMenuInfo = (AdapterContextMenuInfo) menuInfo;
                PreferenceScreen screen = getPreferenceScreen();
                ListAdapter root = screen.getRootAdapter();
                Object item = root.getItem(adapterMenuInfo.position);
                return (Preference) item;
            }
        }
        return null;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        Preference preference = getPreferenceFromMenuInfo(menuInfo);
        if (preference != null) {
            String authority = Server.fromKey(preference.getKey()).getUri().getAuthority();
            if (mRemembered.contains(authority)) {
                menu.add(Menu.NONE, CONTEXT_EDIT_SERVER, Menu.NONE, R.string.edit_server);
                menu.add(Menu.NONE, CONTEXT_FORGET, Menu.NONE, R.string.context_forget);
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Preference preference;
        switch (item.getItemId()) {
            case CONTEXT_FORGET:
                preference = getPreferenceFromMenuInfo(item.getMenuInfo());
                if (preference != null) {
                    forget(Server.fromKey(preference.getKey()));
                }
                return true;
            case CONTEXT_EDIT_SERVER:
                preference = getPreferenceFromMenuInfo(item.getMenuInfo());
                if(preference != null) {
                    ServerInfoDialog d = ServerInfoDialog.editServerInstance(preference.getKey());
                    d.show(getFragmentManager(), DIALOG_EDIT_SERVER);
                }
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private void updateWifiInfo() {
        WifiInfo info = getConnectionInfo();
        if (info != null) {
            mPreferenceWiFi.setChecked(true);
            String ssid = info.getSSID();
            String template = getString(R.string.summary_wifi_connected);
            Object[] objects = {
                ssid != null ? ssid : ""
            };
            mPreferenceWiFi.setSummary(MessageFormat.format(template, objects));
        } else {
            mPreferenceWiFi.setChecked(false);
            mPreferenceWiFi.setSummary(R.string.summary_wifi_disconnected);
        }
    }
    
    private boolean getPauseForCall() {
        switch (getActivity().getPackageManager().getComponentEnabledSetting(PHONE_STATE_RECEIVER)) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return true;
            default:
                return false;
        }
    }

    private void setPauseForCall(boolean enabled) {
        getActivity().getPackageManager().setComponentEnabledSetting(
                PHONE_STATE_RECEIVER,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
    
    private class MyBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateWifiInfo();
        }
    }

}
