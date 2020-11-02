package carlwu.top.lib_device_add;

import android.util.Log;

import com.aliyun.alink.linksdk.channel.core.base.AError;
import com.aliyun.alink.linksdk.channel.mobile.api.IMobileDownstreamListener;
import com.aliyun.alink.linksdk.channel.mobile.api.IMobileRequestListener;
import com.aliyun.alink.linksdk.channel.mobile.api.MobileChannel;
import com.aliyun.iot.aep.sdk.apiclient.IoTAPIClient;
import com.aliyun.iot.aep.sdk.apiclient.IoTAPIClientFactory;
import com.aliyun.iot.aep.sdk.apiclient.callback.IoTCallback;
import com.aliyun.iot.aep.sdk.apiclient.callback.IoTResponse;
import com.aliyun.iot.aep.sdk.apiclient.request.IoTRequest;
import com.aliyun.iot.aep.sdk.apiclient.request.IoTRequestBuilder;
import com.aliyun.iot.aep.sdk.login.ILoginCallback;
import com.aliyun.iot.aep.sdk.login.ILogoutCallback;
import com.aliyun.iot.aep.sdk.login.LoginBusiness;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;

import carlwu.top.lib_device_add.exceptions.AlreadyBoundException;
import carlwu.top.lib_device_add.exceptions.NeedUnbindFirstException;

/**
 * #### 1.登录  LoginBusiness.authCodeLogin
 * #### 2.订阅监听 MobileChannel.getInstance().registerDownstreamListener
 * #### 3.通知网关允许发现子设备  /thing/gateway/permit
 * #### 4.取消监听 MobileChannel.getInstance().unRegisterDownstreamListener
 * #### 5.解绑长连接通道 MobileChannel.getInstance().unBindAccount();
 * #### 6.登出 LoginBusiness.logout();
 */
public class NodeHelper {
    private BindCallback bindCallback;
    private Timer runTimer;
    private TimerTask timeoutTimerTask;
    private final String TAG = "NodeHelper";
    private volatile boolean status;//工作状态

    public NodeHelper(BindCallback bindCallback) {
        this.bindCallback = bindCallback;
    }

    public interface BindCallback {
        /**
         * 调用层返回是否已经处理了解绑操作
         * <p>
         * 调用层需要自行通知管理员账号，解绑该设备的所有绑定关系，否则将会报错：设备已被绑定。
         *
         * @param subIotId
         * @param subProductKey
         * @param subDeviceName
         * @return true:已处理，正常跳过。
         */
        boolean isUnbindRelation(String subIotId, String subProductKey, String subDeviceName);

        /**
         * 失败回调
         *
         * @param e NeedUnbindFirstException 绑定节点前，需要确保强制解除设备上所有绑定关系
         */
        void onFailure(Exception e);

        void onSuccess(String subIotId, String subProductKey, String subDeviceName);
    }

    private int time_second = 60;//超时时间
    private String authCode;
    private String Gateway_IotId;
    private String SubNode_ProductKey;

    /**
     * 开始节点绑定
     *
     * @param authCode           授权码
     * @param Gateway_IotId      网关设备iotId
     * @param SubNode_ProductKey 允许接入网关的子设备产品标识符
     */
    public void startBind(final String authCode, String Gateway_IotId, String SubNode_ProductKey, int time_second) throws InterruptedException {
        if (status) {
            throw new RuntimeException("流程进行中，不可重复startBind。");
        }
        if (time_second < 20 || time_second > 200) {
            throw new RuntimeException("time_second 需要 >=20 <=200");
        }
        Log.d(TAG, "start: ");
        status = true;
        this.authCode = authCode;
        this.Gateway_IotId = Gateway_IotId;
        this.SubNode_ProductKey = SubNode_ProductKey;
        this.time_second = time_second;

        runTimer = new Timer();
        timeoutTimerTask = new TimerTask() {
            @Override
            public void run() {
                handleFailure(new Exception("超时失败"));
            }
        };
        runTimer.schedule(timeoutTimerTask, time_second * 1000);
        unBindChannel();
    }

    /**
     * 结束节点绑定
     */
    public void stopBind() {
        if (!status) {
            return;
        }
        Log.d(TAG, "stop: ");
        status = false;
        this.bindCallback = null;
        cancelWaitForSubDevice();
        if (runTimer != null) {
            runTimer.cancel();
        }
        runTimer = null;
        timeoutTimerTask = null;
        unBindChannel();
    }

    private void authCodeLogin() {
        if (!status) {
            return;
        }
        LoginBusiness.authCodeLogin(authCode, new ILoginCallback() {
            @Override
            public void onLoginSuccess() {
                Log.d(TAG, "authCodeLogin onLoginSuccess: ");
                if (!status) {
                    return;
                }
                runTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        waitForSubDevice();
                    }
                }, 2000);
            }

            @Override
            public void onLoginFailed(int i, String s) {
                Log.e(TAG, "authCodeLogin onLoginFailed: " + s);
                handleFailure(new Exception("authCode登录失败" + s));
            }
        });
    }

    private IMobileDownstreamListener iMobileDownstreamListener;

    /**
     * 等待发现子设备
     */
    private void waitForSubDevice() {
        if (!status) {
            return;
        }
        Log.d(TAG, "waitForSubDevice: ");
        iMobileDownstreamListener = new IMobileDownstreamListener() {
            @Override
            public void onCommand(String s, String s1) {
                Log.d(TAG, "onCommand: " + s + " " + s1);
                if (!status) {
                    return;
                }
                try {
                    final JSONObject jsonObject = new JSONObject(s1);
                    int status = jsonObject.optInt("status", -1);
                    final String subProductKey = jsonObject.getString("subProductKey");
                    final String subDeviceName = jsonObject.getString("subDeviceName");
                    final String subIotId = jsonObject.getString("subIotId");
                    if (status == 0) {
                        cancelWaitForSubDevice();
                        unbindRelation(subIotId, subProductKey, subDeviceName);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public boolean shouldHandle(String s) {
                Log.d(TAG, "shouldHandle: " + s);
                if ("/thing/topo/add/status".equals(s))
                    return true;
                return false;
            }
        };
        MobileChannel.getInstance().registerDownstreamListener(true, iMobileDownstreamListener);
        notifyGatewayOpen();
    }

    private void cancelWaitForSubDevice() {
        if (iMobileDownstreamListener != null) {
            Log.d(TAG, "cancelWaitForSubDevice: ");
            MobileChannel.getInstance().unRegisterDownstreamListener(iMobileDownstreamListener);
        }
        iMobileDownstreamListener = null;
    }


    /**
     * 通知网关允许添加子设备
     */
    private void notifyGatewayOpen() {
        if (!status) {
            return;
        }
        /**
         * time
         * 建议值 20-200 ，网关会在超时时间到了后再退出配网模式
         */
        IoTRequestBuilder builder = new IoTRequestBuilder()
                .setPath("/thing/gateway/permit")
                .setApiVersion("1.0.2")
                .setAuthType("iotAuth")
                .addParam("iotId", Gateway_IotId)
                .addParam("productKey", SubNode_ProductKey)
                .addParam("time", time_second);

        IoTRequest request = builder.build();

        IoTAPIClient ioTAPIClient = new IoTAPIClientFactory().getClient();

        ioTAPIClient.send(request, new IoTCallback() {
            @Override
            public void onFailure(IoTRequest ioTRequest, Exception e) {
                Log.e(TAG, "notifyGatewayOpen onFailure: ", e);
                handleFailure(new Exception("通知网关进入发现节点模式失败", e));
            }

            @Override
            public void onResponse(IoTRequest ioTRequest, IoTResponse ioTResponse) {
                Log.d(TAG, "notifyGatewayOpen onResponse: " + ioTResponse.getCode() + " " + ioTResponse.getLocalizedMsg());
                final int code = ioTResponse.getCode();
                if (code == 200) {
//                    waitForSubDevice();
                } else {
                    handleFailure(new Exception("网关无法进入发现节点模式，code=" + code + " data=" + ioTResponse.getLocalizedMsg()));
                }
            }
        });
    }

    /**
     * 通知管理权限解绑设备的所有绑定关系。
     *
     * @param subIotId
     * @param subProductKey
     * @param subDeviceName
     */
    private void unbindRelation(final String subIotId, final String subProductKey, final String subDeviceName) {
        if (!status) {
            return;
        }
        if (bindCallback != null) {
            boolean unbindRelation = bindCallback.isUnbindRelation(subIotId, subProductKey, subDeviceName);
            if (!status) {
                return;
            }
            if (unbindRelation) {
                bindSubDevice(subProductKey, subDeviceName);
            } else {
                handleFailure(new NeedUnbindFirstException("需要确保已经解除了设备上所有绑定关系"));
            }
        } else {
            handleFailure(new Exception("BindCallback不能为空"));
        }
    }

    /**
     * 通知Ali绑定设备
     *
     * @param productKey 待配网设备productKey
     * @param deviceName 待配网设备deviceName
     */
    private void bindSubDevice(final String productKey, final String deviceName) {
        if (!status) {
            return;
        }
        timeoutTimerTask.cancel();
        IoTRequestBuilder builder = new IoTRequestBuilder()
                .setPath("/awss/time/window/user/bind")
                .setApiVersion("1.0.8")
                .setAuthType("iotAuth")
                .addParam("productKey", productKey)
                .addParam("deviceName", deviceName);

        IoTRequest request = builder.build();

        IoTAPIClient ioTAPIClient = new IoTAPIClientFactory().getClient();
        ioTAPIClient.send(request, new IoTCallback() {
            @Override
            public void onFailure(IoTRequest ioTRequest, Exception e) {
                Log.e(TAG, "bindSubDevice onFailure: ", e);
                handleFailure(new Exception("绑定节点设备失败", e));
            }

            @Override
            public void onResponse(IoTRequest ioTRequest, IoTResponse ioTResponse) {
                Log.d(TAG, "bindSubDevice onResponse: " + ioTResponse.getCode() + " " + ioTResponse.getLocalizedMsg() + " " + ioTResponse.getData());
                if (!status) {
                    return;
                }
                final int code = ioTResponse.getCode();
                if (code == 200) {
                    try {
                        String iotId = ((JSONObject) ioTResponse.getData()).getString("iotId");
                        if (bindCallback != null) {
                            bindCallback.onSuccess(iotId, productKey, deviceName);
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    stopBind();
                } else if (code == 6221) {//设备不在线，请检查设备运行状态
                    Log.d(TAG, "bindSubDevice onResponse: 设备不在线，1秒后重试");
                    runTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            bindSubDevice(productKey, deviceName);
                        }
                    }, 1000);
                } else {
                    if (code == 2064) {//已被绑定错误
                        handleFailure(new AlreadyBoundException(ioTResponse.getLocalizedMsg()));
                    } else {
                        handleFailure(new Exception("绑定阶段失败,code=" + ioTResponse.getCode() + " data:" + ioTResponse.getData()));
                    }
                }
            }
        });
    }

    private void unBindChannel() {
        MobileChannel.getInstance().unBindAccount(new IMobileRequestListener() {
            @Override
            public void onSuccess(String s) {
                Log.d(TAG, "unBindChannel onSuccess: " + s);
                logout();
            }

            @Override
            public void onFailure(AError aError) {
                Log.d(TAG, "unBindChannel onFailure: " + aError);
                logout();
            }
        });
    }

    private void logout() {
        LoginBusiness.logout(new ILogoutCallback() {
            @Override
            public void onLogoutSuccess() {
                Log.d(TAG, "logout onLogoutSuccess: ");
                cleanUserStateSuccess();
            }

            @Override
            public void onLogoutFailed(int i, String s) {
                Log.d(TAG, "logout onLogoutFailed: " + i + " " + s);
                cleanUserStateSuccess();
            }
        });
    }

    private void cleanUserStateSuccess() {
        if (!status) {
            return;
        }
        Log.d(TAG, "cleanUserStateSuccess: ");
        authCodeLogin();
    }

    private void handleFailure(Exception e) {
        if (bindCallback != null) {
            bindCallback.onFailure(e);
        }
        stopBind();
    }
}
