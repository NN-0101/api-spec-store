package com.nn0101.specstore.core.tlog;

import com.yomahub.tlog.constant.TLogConstants;
import com.yomahub.tlog.core.rpc.TLogLabelBean;
import com.yomahub.tlog.core.rpc.TLogRPCHandler;
import jakarta.servlet.http.HttpServletRequest;

/**
 * TLog Web 端核心处理类（单例）
 * <p>
 * 从 HTTP 请求头提取 TraceId/SpanId 等链路信息，绑定到当前线程；
 * 请求结束后清理 ThreadLocal，防止线程复用导致 TraceId 错乱。
 *
 * @author 0101
 * @see TLogFilter
 * @since 2026-03-12
 */
public class TLogWebCommon extends TLogRPCHandler {

    private static volatile TLogWebCommon instance;

    private TLogWebCommon() {}

    public static TLogWebCommon loadInstance() {
        if (instance == null) {
            synchronized (TLogWebCommon.class) {
                if (instance == null) {
                    instance = new TLogWebCommon();
                }
            }
        }
        return instance;
    }

    public void preHandle(HttpServletRequest request) {
        String traceId = request.getHeader(TLogConstants.TLOG_TRACE_KEY);
        String spanId = request.getHeader(TLogConstants.TLOG_SPANID_KEY);
        String preIvkApp = request.getHeader(TLogConstants.PRE_IVK_APP_KEY);
        String preIvkHost = request.getHeader(TLogConstants.PRE_IVK_APP_HOST);
        String preIp = request.getHeader(TLogConstants.PRE_IP_KEY);

        TLogLabelBean labelBean = new TLogLabelBean(preIvkApp, preIvkHost, preIp, traceId, spanId);
        processProviderSide(labelBean);
    }

    public void afterCompletion() {
        cleanThreadLocal();
    }
}