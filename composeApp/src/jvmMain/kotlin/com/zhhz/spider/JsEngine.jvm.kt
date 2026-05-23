package com.zhhz.spider

import org.mozilla.javascript.engine.RhinoScriptEngineFactory
import javax.script.ScriptEngine

actual val ENGINE: ScriptEngine = RhinoScriptEngineFactory().scriptEngine
