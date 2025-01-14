/*
 * ************************************************************
 * 文件：ElegantBusService.java  模块：ElegantBus.ipc  项目：ElegantBus
 * 当前修改时间：2023年02月24日 17:46:20
 * 上次修改时间：2023年02月24日 16:08:06
 * 作者：Cody.yi   https://github.com/codyer
 *
 * 描述：ElegantBus.ipc
 * Copyright (c) 2023
 * ************************************************************
 */

package cody.bus;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


/**
 * 跨进程事件总线支持服务
 * messenger 实现
 */
public class ElegantBusService extends BaseBusService {
    public final static int MSG_REGISTER = 0x01;
    public final static int MSG_UNREGISTER = 0x02;
    public final static int MSG_RESET_STICKY = 0x03;//进程重置sticky
    public final static int MSG_POST_TO_SERVICE = 0x04;//进程分发到service
    public final static String MSG_DATA = "MSG_DATA";
    public final static String MSG_PROCESS_NAME = "MSG_PROCESS_NAME";
    private final List<ProcessCallback> mRemoteCallbackList = new CopyOnWriteArrayList<>();
    private final String mServiceProcessName;
    private final Map<String, EventWrapper> mEventCache = new ConcurrentHashMap<>();
    private final Messenger mServiceMessenger = new Messenger(new ServiceHandler());

    private final class ProcessCallback {
        String processName;
        Messenger messenger;

        ProcessCallback(final String processName, final Messenger messenger) {
            this.processName = processName;
            this.messenger = messenger;
        }

        void call(final EventWrapper eventWrapper, final int what) throws RemoteException {
            Message message = Message.obtain(null, what);
            message.replyTo = mServiceMessenger;
            Bundle data = new Bundle();
            data.putParcelable(ElegantBusService.MSG_DATA, eventWrapper);
            message.setData(data);
            messenger.send(message);
        }
    }

    public ElegantBusService() {
        mServiceProcessName = ElegantBus.getProcessName();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mServiceMessenger.getBinder();
    }

    @SuppressLint("HandlerLeak")
    private class ServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            try {
                // fix BadParcelableException: ClassNotFoundException when unmarshalling
                msg.getData().setClassLoader(getClass().getClassLoader());
                String processName = msg.getData().getString(ElegantBusService.MSG_PROCESS_NAME);
                EventWrapper eventWrapper;
                switch (msg.what) {
                    case MSG_REGISTER:
                        ProcessCallback callback = new ProcessCallback(processName, msg.replyTo);
                        mRemoteCallbackList.add(callback);
                        if (!isServiceProcess(callback)) {
                            postStickyValueToNewProcess(callback);
                        }
                        break;
                    case MSG_UNREGISTER:
                        for (ProcessCallback cb : mRemoteCallbackList) {
                            if (cb.processName.equals(processName) && cb.messenger == msg.replyTo) {
                                mRemoteCallbackList.remove(cb);
                            }
                        }
                        break;
                    case MSG_RESET_STICKY:
                        eventWrapper = msg.getData().getParcelable(ElegantBusService.MSG_DATA);
                        if (eventWrapper != null) {
                            removeEventFromCache(eventWrapper);
                            callbackToOtherProcess(eventWrapper, MultiProcess.MSG_ON_RESET_STICKY);
                        }
                        break;
                    case MSG_POST_TO_SERVICE:
                        eventWrapper = msg.getData().getParcelable(ElegantBusService.MSG_DATA);
                        if (eventWrapper != null) {
                            putEventToCache(eventWrapper);
                            callbackToOtherProcess(eventWrapper, MultiProcess.MSG_ON_POST);
                        }
                        break;
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            super.handleMessage(msg);
        }
    }

    /**
     * 转发事件总线
     *
     * @param eventWrapper 发送值
     */
    private void callbackToOtherProcess(EventWrapper eventWrapper, int what) throws RemoteException {
        int count = mRemoteCallbackList.size();
        for (int i = 0; i < count; i++) {
            ProcessCallback callback = mRemoteCallbackList.get(i);
            if (callback == null) continue;
            if (isSameProcess(callback, eventWrapper.processName)) {
                ElegantLog.d("This is in same process, already posted, Event = " + eventWrapper.toString());
            } else {
                ElegantLog.d("call back " + what + " to other process : " + callback.processName + ", Event = " + eventWrapper.toString());
                callback.call(eventWrapper, what);
            }
        }
    }

    /**
     * 服务进程收到事件先保留，作为其他进程的粘性事件缓存
     *
     * @param eventWrapper 消息
     */
    private void putEventToCache(final EventWrapper eventWrapper) {
        ElegantLog.d("Service receive event, add to cache, Event = " + eventWrapper.toString());
        mEventCache.put(eventWrapper.getKey(), eventWrapper);
    }

    /**
     * 服务进程收到事件先保留，作为其他进程的粘性事件缓存
     *
     * @param eventWrapper 消息
     */
    private void removeEventFromCache(final EventWrapper eventWrapper) {
        ElegantLog.d("Service receive event, remove from cache, Event = " + eventWrapper.toString());
        mEventCache.remove(eventWrapper.getKey());
    }

    /**
     * 转发 粘性事件到新的进程
     *
     * @param callback 进程回调
     * @throws RemoteException 异常
     */
    private void postStickyValueToNewProcess(final ProcessCallback callback) throws RemoteException {
        ElegantLog.d("Post all sticky event to new process : " + callback.processName);
        for (EventWrapper item : mEventCache.values()) {
            callback.call(item, MultiProcess.MSG_ON_POST_STICKY);
        }
    }

    /**
     * 是否为当前管理进程
     *
     * @param callback 进程回调
     * @return 是否需要转发
     */
    private boolean isServiceProcess(ProcessCallback callback) {
        return TextUtils.equals(callback.processName, mServiceProcessName);
    }

    /**
     * 是否为发送进程不需要转发事件总线（原进程中已经处理）
     *
     * @param callback    进程回调
     * @param processName 进程名
     * @return 事件是否来自同一个进程
     */
    private boolean isSameProcess(ProcessCallback callback, String processName) {
        return TextUtils.equals(callback.processName, processName);
    }
}
