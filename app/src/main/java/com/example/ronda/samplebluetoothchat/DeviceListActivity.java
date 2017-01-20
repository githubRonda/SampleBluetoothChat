package com.example.ronda.samplebluetoothchat;


import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Set;

/**
 * 1. 这里继承的是 Activity，而非AppCompatActivity.原因就是： AppCompatActivity 中弃用了 setProgressBarIndeterminateVisibility() 和 setSupportProgressBarIndeterminateVisibility()
 * 2. 在 Manifest 中，这个类的主题是：android:theme="@android:style/Theme.Holo.Light.Dialog"，表示一个对话框
 * 3. 本类(DeviceListActivity) 和 MainActivity 的通信是通过 startActivityForResult() 完成的。在 ListView 的点击事件中把蓝牙的Mac地址封装进Intent中，通过 setResult() 发送给 MainActivity.
 * -- 而 MainActivity 和 BluetoothChatService 的通信是通过 Handler 完成的 （在 MainActivity 中创建 handler， 然后通过构造器传给 BluetoothChatService）
 * 4. 本类的 UI 界面中是有两个 ListView 的，上面的用于显示已匹配过的设备，下面的用于显示新发现的设备。
 * -- 这里是有一个小bug的，若没有已匹配过设备，或者没有发现新设备的话，也会给 ListView 添加一个提示的 ItemView，但是这个 ItemView 是不能点击的，否则会闪退。因为不能提取 Mac 地址
 */
public class DeviceListActivity extends Activity {

    public static String EXTRA_DEVICE_ADDRESS = "device_address";

    private BluetoothAdapter mBtAdapter;

    private ArrayAdapter<String> pairedDevicesArrayAdapter;
    private ArrayAdapter<String> mNewDevicesArrayAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup the window
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS); // 必须要在 setContentView() 之前这样设置，才可以在ActionBar 右上角显示环形的进度条

        setContentView(R.layout.activity_device_list);

        setResult(Activity.RESULT_CANCELED); // 若没有点击 ItemView 的话，设置 resultCode 为 RESULT_CANCELED，这样在 MainFragment 中就不需要处理了

        initView();

        mBtAdapter = BluetoothAdapter.getDefaultAdapter();

        registerBluetoothReceiver();
    }

    private void initView() {
        final Button scanButton = (Button) findViewById(R.id.btn_scan);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doDiscovery();
                scanButton.setVisibility(View.GONE);
            }
        });

        // 已匹配过的设备

        ListView pairedListView = (ListView) findViewById(R.id.paired_devices);
        pairedDevicesArrayAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        pairedListView.setAdapter(pairedDevicesArrayAdapter);
        pairedListView.setOnItemClickListener(mDeviceClickListener);

        // 新的设备
        ListView newDevicesListView = (ListView) findViewById(R.id.new_devices);
        mNewDevicesArrayAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        newDevicesListView.setAdapter(mNewDevicesArrayAdapter);
        newDevicesListView.setOnItemClickListener(mDeviceClickListener);

        findPairedDevices();
    }

    /**
     * 获取已匹配过的蓝牙设备
     */
    private void findPairedDevices() {
        Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            findViewById(R.id.title_paired_devices).setVisibility(View.VISIBLE);
            for (BluetoothDevice device : pairedDevices) {
                pairedDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
            }
        } else {
            pairedDevicesArrayAdapter.add("No devices have been paired"); // 注意这一项若添加进去，则是不能点击的，否则会闪退。这里只是只是为了说明问题而已
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 取消搜索
        if (mBtAdapter != null) {
            mBtAdapter.cancelDiscovery();
        }
        // 注销广播接收器
        unregisterBluetoothReceiver();
    }

    /**
     * Start device discover with the BluetoothAdapter
     */
    private void doDiscovery() {
        // Indicate scanning in the title
        setProgressBarIndeterminateVisibility(true);
        setTitle("scanning for devices...");

        // Turn on sub-title for new devices
        findViewById(R.id.title_new_devices).setVisibility(View.VISIBLE);

        // If we're already discovering, stop it
        if (mBtAdapter.isDiscovering()) {
            mBtAdapter.cancelDiscovery();
        }

        // Request discover from BluetoothAdapter
        mBtAdapter.startDiscovery();
    }


    private AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

            // Cancel discovery because it's costly and we're about to connect
            mBtAdapter.cancelDiscovery();

            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) view).getText().toString();
            String address = info.substring(info.length() - 17);

            // Create the result Intent and include the MAC address
            Intent intent = new Intent();
            intent.putExtra(EXTRA_DEVICE_ADDRESS, address);

            // Set result and finish this Activity
            setResult(Activity.RESULT_OK, intent); // 只有当点击了 ItemView 的时候，才设置 RESULT_OK
            finish();

        }
    };

    /**
     * 注册蓝牙广播接收器
     */
    private void registerBluetoothReceiver() {

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        this.registerReceiver(mReceiver, intentFilter);
    }

    /**
     * 注销广播接收器
     */
    public void unregisterBluetoothReceiver() {
        this.unregisterReceiver(mReceiver);
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // If it's already paired, skip it, because it's been listed already
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    mNewDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                setProgressBarIndeterminateVisibility(false);
                setTitle("select a device to connect");

                if (mNewDevicesArrayAdapter.getCount() == 0) {
                    mNewDevicesArrayAdapter.add("No devices found");// 注意这一项若添加进去，则是不能点击的，否则会闪退。这里只是只是为了说明问题而已
                }
            }
        }
    };
}
