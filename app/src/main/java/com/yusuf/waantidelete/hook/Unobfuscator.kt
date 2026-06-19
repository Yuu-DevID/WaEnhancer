package com.yusuf.waantidelete.hook

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object Unobfuscator {

    private const val TAG = "WaAntiDelete"
    private lateinit var bridge: DexKitBridge

    init {
        System.loadLibrary("dexkit")
    }

    @JvmStatic
    fun init(path: String): Boolean {
        return try {
            bridge = DexKitBridge.create(path)
            true
        } catch (e: Exception) {
            Log.e(TAG, "DexKit init failed", e)
            false
        }
    }

    @JvmStatic
    fun loadFMessageClass(classLoader: ClassLoader): Class<*> {
        val result = bridge.findClass {
            matcher {
                addUsingString("FMessage/getSenderUserJid/key.id")
            }
        }
        if (result.isEmpty()) throw RuntimeException("FMessage class not found")
        val cls = result[0].getInstance(classLoader)
        Log.i(TAG, "FMessage class: ${cls.name}")
        return cls
    }

    @JvmStatic
    fun loadAntiRevokeMessageMethod(classLoader: ClassLoader): Method {
        for (pattern in listOf("msgstore/edit/revoke", "msgstore/revoking/", "msgstore/revoke/missing-old-id", "msgstore/revoking/has-placeholder")) {
            val method = findMethodByString(classLoader, pattern)
            if (method != null) {
                Log.i(TAG, "Revoke method found via '$pattern': ${method.declaringClass.name}->${method.name}")
                return method
            }
        }
        throw RuntimeException("AntiRevoke message method not found")
    }

    @JvmStatic
    fun loadAntiRevokeFStatusMethod(classLoader: ClassLoader): Method {
        val clazz = findFirstClassUsingStrings(classLoader, "RevokeStatusManager/failed")
            ?: throw RuntimeException("RevokeStatus manager not found")
        return ReflectionUtils.findMethod(clazz) { method ->
            method.parameterCount > 0 && method.parameterTypes.any { hasStatusKeyShape(it) }
        }
    }

    @JvmStatic
    fun loadMessageKeyField(classLoader: ClassLoader): Field {
        val fMessageClass = loadFMessageClass(classLoader)
        val result = bridge.findClass {
            matcher {
                fieldCount(3)
                addMethod {
                    name("toString")
                    addUsingString("Key")
                }
            }
        }
        if (result.isEmpty()) throw RuntimeException("MessageKey class not found")

        for (classData in result) {
            val keyClass = classData.getInstance(classLoader)
            for (f in fMessageClass.declaredFields) {
                if (keyClass.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    return f
                }
            }
        }
        throw RuntimeException("MessageKey field not found")
    }

    @JvmStatic
    fun loadUnknownStatusPlaybackMethod(classLoader: ClassLoader): Method {
        val statusPlaybackClass = classLoader.loadClass("com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment")
        val result = bridge.findMethod {
            matcher {
                addUsingString("playbackFragment/refreshCurrentPageSubTitle message is empty")
            }
        }
        if (result.isEmpty()) throw RuntimeException("Status playback refresh method not found")
        val invokes = result[0].invokes
        for (invoke in invokes) {
            val method = invoke.getMethodInstance(classLoader)
            if (Modifier.isStatic(method.modifiers) &&
                method.parameterCount > 1 &&
                method.parameterTypes.contains(statusPlaybackClass) &&
                method.declaringClass == statusPlaybackClass
            ) {
                return method
            }
        }
        throw RuntimeException("UnknownStatusPlayback method not found")
    }

    @JvmStatic
    fun loadStatusPlaybackViewClass(classLoader: ClassLoader): Class<*> {
        val statusHeaderId = appResourceId("status_header")
        val menuId = appResourceId("menu")
        val result = bridge.findClass {
            matcher {
                addMethod {
                    usingNumbers(listOf(statusHeaderId, menuId))
                }
            }
        }
        if (result.isEmpty()) throw RuntimeException("StatusPlaybackViewClass not found")
        return result[0].getInstance(classLoader)
    }

    @JvmStatic
    fun loadViewOnceMethods(classLoader: ClassLoader): Array<Method> {
        val result = bridge.findMethod {
            matcher {
                addUsingString("INSERT_VIEW_ONCE_SQL")
            }
        }
        if (result.isEmpty()) throw RuntimeException("ViewOnce SQL method not found")

        val methodData = result[0]
        val invokes = methodData.invokes
        val methods = mutableListOf<Method>()

        for (m in invokes) {
            try {
                val method = m.getMethodInstance(classLoader)
                if (method.declaringClass.isInterface &&
                    method.declaringClass.declaredMethods.size == 2) {
                    val iface = method.declaringClass
                    val implementingClasses = bridge.findClass {
                        matcher {
                            addInterface(iface.name)
                        }
                    }
                    for (c in implementingClasses) {
                        val clazz = c.getInstance(classLoader)
                        for (m2 in clazz.declaredMethods) {
                            if (m2.parameterCount == 1 &&
                                m2.parameterTypes[0] == Int::class.javaPrimitiveType &&
                                m2.returnType == Void.TYPE) {
                                methods.add(m2)
                            }
                        }
                    }
                    if (methods.isNotEmpty()) return methods.toTypedArray()
                }
            } catch (_: Throwable) {}
        }
        throw RuntimeException("ViewOnce methods not found")
    }

    @JvmStatic
    fun findAllMethodsByString(classLoader: ClassLoader, searchString: String): List<Method> {
        val result = bridge.findMethod {
            matcher {
                addUsingString(searchString)
            }
        }
        return result.mapNotNull {
            try { it.getMethodInstance(classLoader) } catch (_: Throwable) { null }
        }
    }

    private fun findMethodByString(classLoader: ClassLoader, searchString: String): Method? {
        val result = bridge.findMethod {
            matcher {
                addUsingString(searchString)
            }
        }
        if (result.isEmpty()) return null
        for (methodData in result) {
            try {
                return methodData.getMethodInstance(classLoader)
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun findFirstClassUsingStrings(classLoader: ClassLoader, vararg strings: String): Class<*>? {
        val result = bridge.findClass {
            matcher {
                for (value in strings) {
                    addUsingString(value, StringMatchType.Contains)
                }
            }
        }
        if (result.isEmpty()) return null
        return result[0].getInstance(classLoader)
    }

    private fun hasStatusKeyShape(clazz: Class<*>): Boolean {
        return try {
            clazz.getDeclaredField("A00")
            clazz.getDeclaredField("A02")
            clazz.getDeclaredField("A03")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun appResourceId(name: String): Int {
        val app = android.app.AndroidAppHelper.currentApplication()
        val packageName = app?.packageName ?: "com.whatsapp"
        return app?.resources?.getIdentifier(name, "id", packageName) ?: 0
    }
}
