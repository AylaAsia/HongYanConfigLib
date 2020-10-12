package carlwu.top.lib_device_add;

import android.util.Log;

import com.aliyun.alink.linksdk.channel.mobile.api.IMobileDownstreamListener;
import com.aliyun.alink.linksdk.channel.mobile.api.MobileChannel;
import com.aliyun.iot.aep.sdk.apiclient.IoTAPIClient;
import com.aliyun.iot.aep.sdk.apiclient.IoTAPIClientFactory;
import com.aliyun.iot.aep.sdk.apiclient.callback.IoTCallback;
import com.aliyun.iot.aep.sdk.apiclient.callback.IoTResponse;
import com.aliyun.iot.aep.sdk.apiclient.request.IoTRequest;
import com.aliyun.iot.aep.sdk.apiclient.request.IoTRequestBuilder;
import com.aliyun.iot.aep.sdk.login.ILoginCallback;
import com.aliyun.iot.aep.sdk.login.LoginBusiness;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;

import carlwu.top.lib_device_add.exceptions.AlreadyBoundException;
import carlwu.top.lib_device_add.exceptions.NeedUnbindFirstException;

public class NodeHelper {
    private BindCallback bindCallback;
    private Timer runTimer;
    private TimerTask timeoutTimerTask;
    private String TAG = "NodeHelper";
    private boolean status;//工作状态

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

        void onSuccess(String iotId);
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
    public void startBind(final String authCode, String Gateway_IotId, String SubNode_ProductKey, int time_second) {
        if (status) {
            throw new RuntimeException("流程进行中，不可重复startBind。");
        }
        Log.d(TAG, "start: ");
        status = true;
        isCloseGateway = false;
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
        authCodeLogin();
    }

    /**
     * 结束节点绑定
     */
    public void stopBind() {
        Log.d(TAG, "stop: ");
        status = false;
        this.bindCallback = null;
        cancelWaitForSubDevice();
        notifyGatewayClose();
        if (runTimer != null) {
            runTimer.cancel();
        }
        runTimer = null;
        timeoutTimerTask = null;
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
                waitForSubDevice();
                runTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        notifyGatewayOpen();
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

    /**
     * 通知网关允许添加子设备
     */
    private void notifyGatewayOpen() {
        if (!status) {
            return;
        }
        /**
         * time
         * 0：网关一直允许添加子设备
         * 0～65535：网关允许添加子设备的时间长度，单位为秒
         * 65535：网关不允许添加子设备
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

    private IMobileDownstreamListener iMobileDownstreamListener;

    /**
     * 等待发现子设备
     */
    private void waitForSubDevice() {
        Log.d(TAG, "waitForSubDevice: ");
        if (!status) {
            return;
        }
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
                        notifyGatewayClose();
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
    }

    private void cancelWaitForSubDevice() {
        if (iMobileDownstreamListener != null) {
            Log.d(TAG, "cancelWaitForSubDevice: ");
            MobileChannel.getInstance().unRegisterDownstreamListener(iMobileDownstreamListener);
        }
        iMobileDownstreamListener = null;
    }

    private boolean isCloseGateway = false;//标记是否已经通知网关不允许发现子节点

    /**
     * 通知网关不允许添加子设备
     */
    private void notifyGatewayClose() {
        if (isCloseGateway) {
            return;
        }
        Log.d(TAG, "notifyGatewayClose: ");
        /**
         * time
         * 0：网关一直允许添加子设备
         * 0～65535：网关允许添加子设备的时间长度，单位为秒
         * 65535：网关不允许添加子设备
         */
        IoTRequestBuilder builder = new IoTRequestBuilder()
                .setPath("/thing/gateway/permit")
                .setApiVersion("1.0.2")
                .setAuthType("iotAuth")
                .addParam("iotId", Gateway_IotId)
//                .addParam("productKey", SubNode_ProductKey)
                .addParam("time", 65535);

        IoTRequest request = builder.build();

        IoTAPIClient ioTAPIClient = new IoTAPIClientFactory().getClient();

        ioTAPIClient.send(request, new IoTCallback() {
            @Override
            public void onFailure(IoTRequest ioTRequest, Exception e) {
                Log.e(TAG, "notifyGatewayClose onFailure: ", e);
            }

            @Override
            public void onResponse(IoTRequest ioTRequest, IoTResponse ioTResponse) {
                Log.d(TAG, "notifyGatewayClose onResponse: " + ioTResponse.getCode() + " " + ioTResponse.getLocalizedMsg());
            }
        });
        isCloseGateway = true;
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
                Log.d(TAG, "bindSubDevice onResponse: " + ioTResponse.getCode() + " " + ioTResponse.getLocalizedMsg());
                if (!status) {
                    return;
                }
                final int code = ioTResponse.getCode();
                if (code == 200) {
                    try {
                        String iotId = ((JSONObject) ioTResponse.getData()).getString("iotId");
                        if (bindCallback != null) {
                            bindCallback.onSuccess(iotId);
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
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

    private void handleFailure(Exception e) {
        if (bindCallback != null) {
            bindCallback.onFailure(e);
        }
        stopBind();
    }
}
