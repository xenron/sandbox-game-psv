/**
 * Copyright 2016 codestation. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.codestation.henkakuserver;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;
import java.lang.reflect.Method;


public class MainActivity extends AppCompatActivity {

    private static final int DEFAULT_PORT = 8080;

    // INSTANCE OF ANDROID WEB SERVER
    private HenkakuServer henkakuServer;
    private BroadcastReceiver broadcastReceiverNetworkState;
    private static boolean isStarted = false;

    // VIEW
    private CoordinatorLayout coordinatorLayout;
    private EditText editTextPort;
    private FloatingActionButton floatingActionButtonOnOff;
    private View textViewMessage;
    private TextView textViewIpAccess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // INIT VIEW
        coordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinatorLayout);
        editTextPort = (EditText) findViewById(R.id.editTextPort);
        textViewMessage = findViewById(R.id.textViewMessage);
        textViewIpAccess = (TextView) findViewById(R.id.textViewIpAccess);
        setIpAccess();
        floatingActionButtonOnOff = (FloatingActionButton) findViewById(R.id.floatingActionButtonOnOff);
        floatingActionButtonOnOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isConnectedInWifi(false)) {
                    if (!isStarted && startAndroidWebServer()) {
                        isStarted = true;
                        textViewMessage.setVisibility(View.VISIBLE);
                        floatingActionButtonOnOff.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.colorGreen));
                        editTextPort.setEnabled(false);
                    } else if (stopAndroidWebServer()) {
                        isStarted = false;
                        textViewMessage.setVisibility(View.INVISIBLE);
                        floatingActionButtonOnOff.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.colorRed));
                        editTextPort.setEnabled(true);
                    }
                } else {
                    Snackbar.make(coordinatorLayout, getString(R.string.wifi_message), Snackbar.LENGTH_LONG).show();
                }
            }
        });

        // INIT BROADCAST RECEIVER TO LISTEN NETWORK STATE CHANGED
        initBroadcastReceiverNetworkStateChanged();
    }

    //region Start And Stop AndroidWebServer
    private boolean startAndroidWebServer() {
        if (!isStarted) {
            int port = getPortFromEditText();
            try {
                if (port == 0) {
                    throw new Exception();
                }

                henkakuServer = new HenkakuServer(this, port);
                henkakuServer.setIpAddress(getIpAccess(false));
                henkakuServer.start();
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                Snackbar.make(coordinatorLayout, "The PORT " + port + " doesn't work, please change it between 1000 and 9999.", Snackbar.LENGTH_LONG).show();
            }
        }
        return false;
    }

    private boolean stopAndroidWebServer() {
        if (isStarted && henkakuServer != null) {
            henkakuServer.stop();
            return true;
        }
        return false;
    }
    //endregion

    //region Private utils Method
    private void setIpAccess() {
        textViewIpAccess.setText(getIpAccess(true));
        if (isStarted && henkakuServer != null) {
            henkakuServer.setIpAddress(getIpAccess(false));
        }
    }

    private void initBroadcastReceiverNetworkStateChanged() {
        final IntentFilter filters = new IntentFilter();
        filters.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        filters.addAction("android.net.wifi.STATE_CHANGE");
        filters.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");

        broadcastReceiverNetworkState = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                setIpAccess();
            }
        };
        super.registerReceiver(broadcastReceiverNetworkState, filters);
    }

    private String getIpAccess(boolean url) {
        String formatedIpAddress;

        if (isConnectedInWifi(true)) {
            WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            formatedIpAddress = String.format(Locale.getDefault(), "%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        } else {
            formatedIpAddress = "192.168.43.1";
        }

        if (url) {
            return "http://" + formatedIpAddress + ":";
        } else {
            return formatedIpAddress;
        }
    }

    private int getPortFromEditText() {
        String valueEditText = editTextPort.getText().toString();
        return (valueEditText.length() > 0) ? Integer.parseInt(valueEditText) : DEFAULT_PORT;
    }

    public boolean isConnectedInWifi(boolean wifiOnly) {
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        NetworkInfo networkInfo = ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        boolean wifiEnabled = networkInfo != null && networkInfo.isAvailable() && networkInfo.isConnected()
                && wifiManager.isWifiEnabled() && networkInfo.getTypeName().equals("WIFI");

       if(!wifiOnly && !wifiEnabled) {
           try {
               final Method method = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
               method.setAccessible(true);
               wifiEnabled = (Boolean) method.invoke(wifiManager);
           } catch(Exception e) {
               e.printStackTrace();
           }
       }

       return wifiEnabled;
    }
    //endregion

    public boolean onKeyDown(int keyCode, KeyEvent evt) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isStarted) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.warning)
                        .setMessage(R.string.dialog_exit_message)
                        .setPositiveButton(getResources().getString(android.R.string.ok), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                finish();
                            }
                        })
                        .setNegativeButton(getResources().getString(android.R.string.cancel), null)
                        .show();
            } else {
                finish();
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAndroidWebServer();
        isStarted = false;
        if (broadcastReceiverNetworkState != null) {
            unregisterReceiver(broadcastReceiverNetworkState);
        }
    }

}
