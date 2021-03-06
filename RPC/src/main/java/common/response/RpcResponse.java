package common.response;



import common.config.ResponseCode;
import lombok.Data;

import java.io.Serializable;

// 响应对象
@Data
public class RpcResponse<T> implements Serializable {

    // 响应对应的请求号
    private String requestId;

    // 响应状态码
    private Integer statusCode;

    // 响应状态补充信息（如果响应失败，必须有提示）
    private String message;

    // 响应数据
    private T data;

    /**
     * 响应成功，返回状态码和响应数据
     */
    // 调用成功，返回数据
    public static <T> RpcResponse<T> success(T data, String requestId) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setStatusCode(ResponseCode.SUCCESS.getCode());
        response.setData(data);
        response.setRequestId(requestId);
        return response;
    }

    /**
     * 响应失败，返回状态码和错误提示信息
     * @param code
     * @param <T>
     * @return
     */
    // 如果调用失败，则无法封装数据
    public static <T> RpcResponse<T> fail(ResponseCode code, String requestId) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setStatusCode(code.getCode()); // 封装状态码
        response.setMessage(code.getMessage()); // 封装错误提示信息
        response.setRequestId(requestId);
        return response;
    }
}
