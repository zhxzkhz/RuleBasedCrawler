package com.zhhz.spider

import com.sun.script.javascript.RhinoScriptEngine
import javax.script.ScriptEngine

actual val ENGINE: ScriptEngine = RhinoScriptEngine()