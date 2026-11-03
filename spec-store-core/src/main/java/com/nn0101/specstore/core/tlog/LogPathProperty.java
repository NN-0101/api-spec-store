package com.nn0101.specstore.core.tlog;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * Logback 日志路径动态属性
 * <p>
 * 根据操作系统返回日志存储路径：Mac → {user.home}/logs，Windows → logs，Linux → /{user.name}/logs。
 * 在 logback-spring.xml 中通过 ${logPath} 引用。
 *
 * @author 0101
 * @since 2026-03-12
 */
public class LogPathProperty extends PropertyDefinerBase {

    public LogPathProperty() {}

    @Override
    public String getPropertyValue() {
        String osName = System.getProperty("os.name");
        String userName = System.getProperty("user.name");

        if (osName.startsWith("Mac OS")) {
            return System.getProperty("user.home") + "/logs";
        } else if (osName.startsWith("Windows")) {
            return "logs";
        } else {
            return String.format("/%s/logs", userName);
        }
    }
}