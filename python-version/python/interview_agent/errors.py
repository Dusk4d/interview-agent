from __future__ import annotations


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, details: list[str] | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.details = details or []


def validation(message: str) -> ApiError:
    return ApiError(400, "VALIDATION_ERROR", message)


def not_found(kind: str, object_id: str) -> ApiError:
    return ApiError(404, "NOT_FOUND", f"未找到{kind}：{object_id}")


def invalid_state(message: str) -> ApiError:
    return ApiError(409, "INVALID_SESSION_STATE", message)


def invalid_request_body(message: str = "请求体或字段类型不正确。") -> ApiError:
    return ApiError(400, "INVALID_REQUEST_BODY", message)


def parse_error(code: str, message: str) -> ApiError:
    return ApiError(422, code, message)


def file_too_large(message: str = "上传文件超过大小限制（默认 10MB），请压缩后重试。") -> ApiError:
    return ApiError(413, "FILE_TOO_LARGE", message)
