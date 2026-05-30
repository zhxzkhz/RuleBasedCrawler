package com.zhhz.spider

import com.sun.script.javascript.RhinoScriptEngine
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import javax.script.ScriptEngine
import javax.script.SimpleBindings

actual val ENGINE: ScriptEngine = RhinoScriptEngine()