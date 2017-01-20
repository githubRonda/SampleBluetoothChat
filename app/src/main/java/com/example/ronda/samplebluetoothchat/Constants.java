package com.example.ronda.samplebluetoothchat;

/**
 * Author: Ronda(1575558177@qq.com)
 * Date: 2017/01/08
 * Version: v1.0
 */
// 其实这个接口也可以直接放到 BluetoothChatService 类中，作为一个内部接口
public interface Constants {

    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5; // 连接时失败 和 通讯过程中 中断 的情况. 表示 message 中的 what 值

    // Key names received from the BluetoothChatService Handler
    public static final String TOAST = "toast";
}
