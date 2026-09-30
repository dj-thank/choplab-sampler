package com.choplab.sampler.source.newpipe

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import java.net.URLClassLoader

class NewPipeRhinoCompatibilityTest {
    @Test fun actualUpstreamJavascriptInterpreterDoesNotRequireOptionalDesktopBridges() {
        // This is a JVM linkage check, not an Android/ART device result. It specifically protects
        // the optional missing classes documented by the upstream application's R8 rules.
        val optional = listOf("java.beans.", "javax.script.", "jdk.dynalink.")
        val jars = arrayOf(NewPipe::class.java.protectionDomain.codeSource.location,
            Class.forName("org.mozilla.javascript.Context").protectionDomain.codeSource.location)
        object : URLClassLoader(jars, null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (optional.any(name::startsWith)) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }.use { loader ->
            optional.zip(listOf("BeanInfo", "ScriptEngine", "DynamicLinker")).forEach { (prefix, name) ->
                try { loader.loadClass(prefix + name); fail("Optional bridge must be absent") }
                catch (_: ClassNotFoundException) { }
            }
            val js = loader.loadClass("org.schabi.newpipe.extractor.utils.JavaScript")
            val function = "function fixture(x) { return JSON.stringify({sound: x.split('').reverse().join(''), n: Math.round(1.6)}); }"
            js.getMethod("compileOrThrow", String::class.java).invoke(null, function)
            val answer = js.getMethod("run", String::class.java, String::class.java, Array<String>::class.java)
                .invoke(null, function, "fixture", arrayOf("audio"))
            assertEquals("{\"sound\":\"oidua\",\"n\":2}", answer)
        }
    }
}
