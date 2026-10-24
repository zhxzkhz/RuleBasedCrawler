package com.zhhz.spider

import org.mozilla.javascript.engine.RhinoScriptEngineFactory
import javax.script.ScriptEngine

/**
 * 与桌面端统一使用 Mozilla 官方 rhino-engine。
 *
 * 此前 Android 使用 gedoor 旧版引擎（com.sun.script.javascript.RhinoScriptEngine），
 * 其 Java 值包装语义与桌面不同：脚本里对 "java_url.replace() 等返回的 Java 字符串
 * 拼接出的结果" 调用字符串方法会直接 TypeError（哔咔 headerScript 实测：桌面通过、
 * Android 报"找不到函数 toLowerCase"，PicaHeaderScriptReproTest 固化了该差异）。
 * 统一引擎后，桌面规则 IDE 调试通过的脚本在 Android 行为完全一致。
 */
actual val ENGINE: ScriptEngine = RhinoScriptEngineFactory().scriptEngine
