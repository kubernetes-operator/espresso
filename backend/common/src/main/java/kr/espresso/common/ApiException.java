package kr.espresso.common;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String what) { return new ApiException(404, "NOT_FOUND", what + "을(를) 찾을 수 없습니다."); }
    public static ApiException forbidden(String msg) { return new ApiException(403, "FORBIDDEN", msg); }
    public static ApiException bad(String msg) { return new ApiException(400, "BAD_REQUEST", msg); }
    public static ApiException conflict(String msg) { return new ApiException(409, "CONFLICT", msg); }
}
