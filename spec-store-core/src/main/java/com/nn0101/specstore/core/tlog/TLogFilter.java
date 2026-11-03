package com.nn0101.specstore.core.tlog;

import com.yomahub.tlog.constant.TLogConstants;
import com.yomahub.tlog.context.TLogContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * TLog 链路追踪过滤器
 * <p>
 * 在每个 HTTP 请求的入口提取/生成 TraceId，设置到响应头，请求结束后清理上下文。
 * 非 HTTP 请求直接放行。
 *
 * @author 0101
 * @see TLogWebCommon
 * @see LogConfig
 * @since 2026-03-12
 */
public class TLogFilter implements Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {

        if (servletRequest instanceof HttpServletRequest && servletResponse instanceof HttpServletResponse) {
            HttpServletRequest request = (HttpServletRequest) servletRequest;
            HttpServletResponse response = (HttpServletResponse) servletResponse;

            try {
                TLogWebCommon.loadInstance().preHandle(request);
                String traceId = TLogContext.getTraceId();
                response.addHeader(TLogConstants.TLOG_TRACE_KEY, traceId);
                filterChain.doFilter(servletRequest, servletResponse);
                return;
            } finally {
                TLogWebCommon.loadInstance().afterCompletion();
            }
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }
}