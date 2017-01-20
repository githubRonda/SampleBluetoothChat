package com.example.ronda.samplebluetoothchat;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.socks.library.KLog;

/**
 *  1. MainActivity 和 BluetoothChatService 的通信是通过 Handler 完成的 （在 MainActivity 中创建 handler， 然后通过构造器传给 BluetoothChatService）
 *  2. MainActivity 和 DeviceListActivity 的通信是通过 startActivityForResult() 完成的
 */
public class MainActivity extends AppCompatActivity {

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE_SECURE   = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT               = 3;

    // Layout Views
    private ListView mConversationListView;
    private EditText mOutEditText;
    private Button   mSendButton;

    private String mConnectedDeviceName = null;
    private ArrayAdapter<String> mConversationArrayAdapter;
    private BluetoothAdapter mBluetoothAdapter = null;

    private BluetoothChatService mChatService = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        KLog.init(true, "liu"); // 使用Klog

        mConversationListView = (ListView) findViewById(R.id.in);
        mOutEditText = (EditText) findViewById(R.id.edit_text_out);
        mSendButton = (Button) findViewById(R.id.btn_send);

        mConversationArrayAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        mConversationListView.setAdapter(mConversationArrayAdapter);

        // 监听软键盘的 Enter 键
        mOutEditText.setOnEditorActionListener(mWriteListener);
        mSendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                EditText edit = (EditText) findViewById(R.id.edit_text_out);
                String message = edit.getText().toString();
                sendMessage(message);
            }
        });

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_SHORT).show();
            finish();
        }

        openBluetooth();

        mChatService = new BluetoothChatService(mHandler); // 创建 BluetoothChatService 对象，然后在 onResume 启动
    }


    /**
     * 开启蓝牙
     */
    public void openBluetooth() {
        if (mBluetoothAdapter != null) {
            if (!mBluetoothAdapter.isEnabled()) {
                //请求用户开启
                Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableIntent, REQUEST_ENABLE_BT); //会有一个对话框等待用户确认
                //mBluetoothAdapter.enable();//直接开启蓝牙
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mChatService != null) {
            KLog.e("onDestroy");
            mChatService.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        KLog.d("onResume" + "  mChatService == null -->" + (mChatService == null));

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            KLog.d("onResume" + "  mChatService == null -->" + (mChatService == null) + "  state == none:" + (mChatService.getState() == BluetoothChatService.STATE_NONE));
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothChatService.STATE_NONE && mBluetoothAdapter.isEnabled()) {
                // Start the Bluetooth chat services
                mChatService.start();
            }
        }
    }

    private TextView.OnEditorActionListener mWriteListener = new TextView.OnEditorActionListener() {
        @Override
        public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {

            // If the action is a key-up event on the return key, send the message
            if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
                String message = v.getText().toString();
                sendMessage(message);
            }
            return true;
        }
    };

    /**
     * Makes this device discoverable.
     * 让该设备可被搜索
     */
    private void ensureDiscoverable() {
        if (mBluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent disconverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            disconverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(disconverableIntent);
        }
    }


    /**
     * Sends a message.
     *
     * @param message A string of text to send.
     */
    private void sendMessage(String message) {
        // Check that we're actually connected before trying anything
        if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
            Toast.makeText(this, "You are not connected to a device", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0) {
            // Get the message bytes and tell the BluetoothChatService to write
            byte[] send = message.getBytes();
            mChatService.write(send);

            //clear the edit text field
            mOutEditText.setText("");
        }
    }

    /**
     * Updates the status on the action bar.
     */
    private void setStatus(CharSequence subTitle) {

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setSubtitle(subTitle);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AppCompatActivity activity = MainActivity.this;
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothChatService.STATE_CONNECTED:
                            mConnectedDeviceName = msg.obj.toString();// 此时 obj 的值是 remoteDeviceName, 不为 null
                            setStatus("connect to " + mConnectedDeviceName);
                            mConversationArrayAdapter.clear();
                            Toast.makeText(activity, "connected to " + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            setStatus("connecting...");
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                        case BluetoothChatService.STATE_NONE:
                            setStatus("not connected");
                            break;
                    }
                    break;

                case Constants.MESSAGE_WRITE:
                    String writeMessage = (String) msg.obj;
                    mConversationArrayAdapter.add("Me:   " + writeMessage);
                    break;

                case Constants.MESSAGE_READ:
//                    byte[] readBuf = (byte[]) msg.obj;
//                    String readMessage = new String(readBuf, 0, msg.arg1);
                    String readMessage = (String) msg.obj;
                    mConversationArrayAdapter.add(mConnectedDeviceName + ":   " + readMessage);
                    break;

                case Constants.MESSAGE_TOAST: // 接收连接时失败 和 已连接后又中断 的情况
                    Toast.makeText(activity, msg.getData().getString(Constants.TOAST), Toast.LENGTH_SHORT).show();
            }
        }
    };


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {

            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    //setupChat();

                } else {
                    Toast.makeText(this, "Bluetooth was not enabled. Leaving Bluetooth Chat.", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;

            case REQUEST_CONNECT_DEVICE_SECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data);
                }
                break;
        }
    }

    /**
     * Establish connection with other divice
     *
     * @param data   An {@link Intent} with {@link DeviceListActivity#EXTRA_DEVICE_ADDRESS} extra.
     */
    private void connectDevice(Intent data) {
        // Get the device MAC address
        String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);

        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

        // Attempt to connect to the device
        mChatService.connect(device);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.secure_connect_scan: {
                // Launch the DeviceListActivity to see devices and do scan
                Intent serverIntent = new Intent(this, DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE);
                return true;
            }
            case R.id.discoverable: {
                // Ensure this device is discoverable by others
                ensureDiscoverable();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }
}
