package com.yusuf.waantidelete.hook

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method

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
        // WhatsApp 2.26.23.74: "msgstore/edit/revoke" removed
        // Use "msgstore/revoke" which is in the DB store method
        // But we need the ACTUAL incoming handler
        // Search for multiple patterns
        val patterns = listOf(
            "msgstore/revoke",
            "msgstore/revoking",
            "FMessageRevokedFactory/cloneIncomingRevokeMessage"
        )
        for (s in patterns) {
            val methods = findAllMethodsByString(classLoader, s)
            for (m in methods) {
                Log.i(TAG, "Found method with '$s': ${m.declaringClass.name}->${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
        }

        // The actual revoke handler in 0nc takes (0nc, 0pU, int, boolean) -> boolean
        // But we need to find it by string search
        // Try "msgstore/revoke" first
        for (s in listOf("msgstore/revoke/missing-old-id", "msgstore/revoking/has-placeholder")) {
            val method = findMethodByString(classLoader, s)
            if (method != null) {
                Log.i(TAG, "Revoke method found via '$s': ${method.declaringClass.name}->${method.name}")
                return method
            }
        }

        throw RuntimeException("AntiRevoke message method not found")
    }

    @JvmStatic
    fun loadAntiRevokeFStatusMethod(classLoader: ClassLoader): Method {
        val result = bridge.findMethod {
            matcher {
                addUsingString("RevokeStatusManager/failed")
            }
        }
        if (result.isEmpty()) throw RuntimeException("AntiRevoke FStatus method not found")

        // Find the method that takes a parameter with fields A00, A01, A02, A03
        for (methodData in result) {
            try {
                val method = methodData.getMethodInstance(classLoader)
                Log.i(TAG, "RevokeStatus method candidate: ${method.declaringClass.name}->${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                if (method.parameterCount > 0) {
                    // Any method with params should work - just find the right one
                    return method
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to load method: ${e.message}")
            }
        }
        throw RuntimeException("AntiRevoke FStatus method not found")
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
}
