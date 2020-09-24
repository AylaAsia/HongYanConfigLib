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
import com.aliyun.iot.aep.sdk.framework.AApplication;
import com.aliyun.iot.aep.sdk.login.ILoginCallback;
import com.aliyun.iot.aep.sdk.login.LoginBusiness;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;

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

        void onFailure(Exception e);

        void onSuccess(String iotId);
    }

    private int time_second = 60;//超时时间
    private String authCode;
    private String Gateway_IotId;
    private String SubNode_ProductKey;

    /**
     * @param Gateway_IotId      网关设备iotId
     * @param SubNode_ProductKey 允许接入网关的子设备产品标识符
     */
    public void start(final String authCode, String Gateway_IotId, String SubNode_ProductKey, int time_second) {
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
        authCodeLogin();
    }

    public void stop() {
        Log.d(TAG, "stop: ");
        status = false;
        this.bindCallback = null;
        if (iMobileDownstreamListener != null) {
            MobileChannel.getInstance().unRegisterDownstreamListener(iMobileDownstreamListener);
        }
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
                try {
                    notifyGatewayOpen();
                } catch (Exception e) {
                    Log.e(TAG, "onSuccess: ", e);
                    handleFailure(e);
                }
            }

            @Override
            public void onLoginFailed(int i, String s) {
                Log.e(TAG, "authCodeLogin onLoginFailed: " + s);
                handleFailure(new Exception("authCode登录失败" + s));
            }
        });
    }

    private void notifyGatewayOpen() {
        if (!status) {
            return;
        }
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
                Log.e(TAG, "enableGatewayFind onFailure: ", e);
                handleFailure(new Exception("通知网关进入发现节点模式失败", e));
            }

            @Override
            public void onResponse(IoTRequest ioTRequest, IoTResponse ioTResponse) {
                Log.d(TAG, "enableGatewayFind onResponse: " + ioTResponse.getCode() + " " + ioTResponse.getMessage());
                final int code = ioTResponse.getCode();
                if (code == 200) {
                    waitForSubDevice();
                } else {
                    handleFailure(new Exception("网关无法进入发现节点模式，code=" + code + " data=" + ioTResponse.getMessage()));
                }
            }
        });
    }

    private IMobileDownstreamListener iMobileDownstreamListener;

    private void waitForSubDevice() {
        if (!status) {
            return;
        }
        iMobileDownstreamListener = new IMobileDownstreamListener() {
            @Override
            public void onCommand(String s, String s1) {
                Log.d(TAG, "onCommand: " + s + " " + s1);
                try {
                    JSONObject jsonObject = new JSONObject(s1);
                    int status = jsonObject.optInt("status", -1);
                    final String subProductKey = jsonObject.getString("subProductKey");
                    final String subDeviceName = jsonObject.getString("subDeviceName");
                    if (status == 0) {
                        timeoutTimerTask.cancel();
                        MobileChannel.getInstance().unRegisterDownstreamListener(iMobileDownstreamListener);
                        runTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                bindSubDevice(subProductKey, subDeviceName);
                            }
                        }, 5_000);//等待子设备恢复在线状态，避免6221错误，设备不在线。也可以尝试重试几次的方式
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
                //{"iotId":"WYHTcXM8grKO9eahl1JA000000","categoryKey":"WallSwitch"}
                Log.d(TAG, "bindSubDevice onResponse: " + ioTResponse.getCode() + " " + ioTResponse.getMessage());
                if (ioTResponse.getCode() == 200) {
                    try {
                        String iotId = ((JSONObject) ioTResponse.getData()).getString("iotId");
                        if (bindCallback != null) {
                            bindCallback.onSuccess(iotId);
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else {
                    handleFailure(new Exception("绑定节点设备失败,code=" + ioTResponse.getCode() + " data=" + ioTResponse.getMessage()));
                }
            }
        });
    }

    private void handleFailure(Exception e) {
        bindCallback.onFailure(e);
        stop();
    }
}
