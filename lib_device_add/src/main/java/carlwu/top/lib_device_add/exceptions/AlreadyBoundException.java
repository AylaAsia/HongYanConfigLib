package carlwu.top.lib_device_add.exceptions;

/**
 * 设备已被绑定异常
 */
public class AlreadyBoundException extends Exception {
    public AlreadyBoundException(String message) {
        super(message);
    }
}