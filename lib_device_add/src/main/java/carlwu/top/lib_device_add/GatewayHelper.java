package carlwu.top.lib_device_add;

import android.app.Application;
import android.util.Log;

import com.aliyun.alink.business.devicecenter.api.add.DeviceInfo;
import com.aliyun.alink.business.devicecenter.api.discovery.DiscoveryType;
import com.aliyun.alink.business.devicecenter.api.discovery.GetTokenParams;
import com.aliyun.alink.business.devicecenter.api.discovery.GetTokenResult;
import com.aliyun.alink.business.devicecenter.api.discovery.IDeviceDiscoveryListener;
import com.aliyun.alink.business.devicecenter.api.discovery.IOnTokenGetListerner;
import com.aliyun.alink.business.devicecenter.api.discovery.LocalDeviceMgr;
import com.aliyun.alink.business.devicecenter.base.DCErrorCode;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import carlwu.top.lib_device_add.exceptions.AlreadyBoundException;

public interface GatewayHelper {
    public static final String TAG = "GatewayHelper";

    public static class DiscoverHelper {
        Application application;
        DiscoverCallback callback;

        public DiscoverHelper(Application application, DiscoverCallback callback) {
            this.application = application;
            this.callback = callback;
        }

        public void startDiscoverGateway() {
            LocalDeviceMgr.getInstance()
                    .startDiscovery(application, EnumSet.of(DiscoveryType.LOCAL_ONLINE_DEVICE, DiscoveryType.CLOUD_ENROLLEE_DEVICE, DiscoveryType.SOFT_AP_DEVICE, DiscoveryType.BEACON_DEVICE), null, new IDeviceDiscoveryListener() {
                        @Override
                        public void onDeviceFound(DiscoveryType discoveryType, List<DeviceInfo> list) {
                            List<Map<String, String>> data = new ArrayList<>();
                            for (DeviceInfo deviceInfo : list) {
                                Map<String, String> bean = new HashMap<>();
                                bean.put("productKey", deviceInfo.productKey);
                                bean.put("deviceName", deviceInfo.deviceName);
                                bean.put("iotId", deviceInfo.iotId);
                                bean.put("regIotId", deviceInfo.regIotId);
                                data.add(bean);
                            }
                            callback.onDeviceFound(discoveryType.getType(), data);
                        }
                    });
        }

        public void stopDiscoverGateway() {
            application = null;
            callback = null;
            LocalDeviceMgr.getInstance().stopDiscovery();
        }
    }

    public static class BindHelper {
        private Timer runTimer;
        private TimerTask timeoutTimerTask;

        Application application;
        BindCallback callback;
        private boolean status;//工作状态

        public BindHelper(AApplication application, BindCallback callback) {
            this.application = application;
            this.callback = callback;
        }

        private String authCode;
        private String productKey;
        private String deviceName;
        private String deviceToken;

        /**
         * 开始网关绑定
         *
         * @param authCode    授权码
         * @param productKey  鸿雁体系的productKey
         * @param deviceName  鸿雁体系的deviceName
         * @param time_second
         */
        public void startBind(final String authCode, final String productKey, final String deviceName, int time_second) {
            if (status) {
                throw new RuntimeException("流程进行中，不可重复startBind。");
            }
            Log.d(TAG, "startBind: ");
            status = true;
            this.authCode = authCode;
            this.productKey = productKey;
            this.deviceName = deviceName;
            runTimer = new Timer();
            timeoutTimerTask = new TimerTask() {
                @Override
                public void run() {
                    handleFailure(new Exception("超时失败"));
                }
            };

            runTimer.schedule(timeoutTimerTask, time_second * 1000);

            GetTokenParams getTokenParams = new GetTokenParams();
            getTokenParams.productKey = productKey;
            getTokenParams.deviceName = deviceName;
            LocalDeviceMgr.getInstance().getDeviceToken(application, getTokenParams, new IOnTokenGetListerner() {
                @Override
                public void onSuccess(GetTokenResult getTokenResult) {
                    Log.d(TAG, "getDeviceToken onSuccess: " + getTokenResult.token);
                    deviceToken = getTokenResult.token;
                    authCodeLogin();
                }

                @Override
                public void onFail(DCErrorCode dcErrorCode) {
                    Log.d(TAG, "getDeviceToken onFail: " + dcErrorCode);
                    handleFailure(new Exception("获取token失败：" + dcErrorCode));
                }
            });
        }

        /**
         * 结束网关绑定
         */
        public void stopBind() {
            Log.d(TAG, "stopBind: ");
            status = false;
            application = null;
            callback = null;
            if (runTimer != null) {
                runTimer.cancel();
            }
            runTimer = null;
            timeoutTimerTask = null;
            LocalDeviceMgr.getInstance().stopGetDeviceToken();
        }

        private void handleFailure(Exception e) {
            if (callback != null) {
                callback.onFailure(e);
            }
            stopBind();
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
                            real_bind();
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

        private void real_bind() {
            if (!status) {
                return;
            }
            IoTRequestBuilder builder = new IoTRequestBuilder()
                    .setPath("/awss/token/user/bind")
                    .setApiVersion("1.0.8")
                    .setAuthType("iotAuth")
                    .addParam("productKey", productKey)
                    .addParam("deviceName", deviceName)
                    .addParam("token", deviceToken);

            IoTRequest request = builder.build();

            IoTAPIClient ioTAPIClient = new IoTAPIClientFactory().getClient();
            ioTAPIClient.send(request, new IoTCallback() {
                @Override
                public void onFailure(IoTRequest ioTRequest, Exception e) {
                    Log.e(TAG, "bind onFailure: ", e);
                    handleFailure(new Exception("绑定阶段失败", e));
                }

                @Override
                public void onResponse(IoTRequest ioTRequest, IoTResponse ioTResponse) {
                    Log.d(TAG, "bind onResponse:" + ioTResponse.getCode() + " data:" + ioTResponse.getData());
                    if (!status) {
                        return;
                    }
                    final int code = ioTResponse.getCode();
                    if (code == 200) {
                        try {
                            String iotId = ((JSONObject) ioTResponse.getData()).getString("iotId");
                            if (callback != null) {
                                callback.onBindSuccess(iotId);
                            }
                            stopBind();
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
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
    }

    public interface DiscoverCallback {
        void onDeviceFound(int type, List<Map<String, String>> data);
    }

    public interface BindCallback {
        void onFailure(Exception e);

        void onBindSuccess(String iotId);
    }
}
