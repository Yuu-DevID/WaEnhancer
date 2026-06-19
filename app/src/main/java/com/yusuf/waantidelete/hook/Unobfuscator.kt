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
            val fields = ReflectionUtils.getFieldsByExtendType(fMessageClass, keyClass)
            if (fields.isNotEmpty()) {
                val field = fields[fields.size - 1]
                field.isAccessible = true
                return field
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

    // ── HideSeen DexKit methods ──────────────────────────────────────────

    @JvmStatic
    fun loadHideViewSendReadJob(classLoader: ClassLoader): Method {
        // Find class ending with "SendReadReceiptJob"
        val sendReadJobClass = findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "SendReadReceiptJob")
            ?: throw RuntimeException("SendReadReceiptJob class not found")

        // Find method in that class using string "receipt"
        val classData = bridge.getClassData(sendReadJobClass)
            ?: throw RuntimeException("SendReadReceiptJob classData not found")

        var methodResult = classData.findMethod {
            matcher {
                addUsingString("receipt", StringMatchType.Equals)
            }
        }
        if (methodResult.isEmpty()) {
            // Try super class
            val superClassData = classData.superClass ?: throw RuntimeException("No super class for SendReadReceiptJob")
            methodResult = superClassData.findMethod {
                matcher {
                    addUsingString("receipt", StringMatchType.Equals)
                }
            }
        }
        if (methodResult.isEmpty()) throw RuntimeException("HideViewSendReadJob method not found")
        return methodResult[0].getMethodInstance(classLoader)
    }

    @JvmStatic
    fun loadReceiptMethod(classLoader: ClassLoader): Method {
        val classDeviceJid = findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.DeviceJid")
            ?: throw RuntimeException("DeviceJid class not found")
        val classProtocolTreeNode = findFirstClassUsingStrings(classLoader, "ProtocolTreeNode/getAttributeJid")
            ?: throw RuntimeException("ProtocolTreeNode class not found")

        val methods = bridge.findMethod {
            matcher {
                addUsingString("receipt")
                returnType(classProtocolTreeNode)
            }
        }
        for (method in methods) {
            val params = method.paramTypeNames
            val hasRequiredParams = params.any { it.contains(classDeviceJid.name) || it.endsWith("DeviceJid") }
            if (hasRequiredParams) {
                return method.getMethodInstance(classLoader)
            }
        }
        throw RuntimeException("Receipt method not found")
    }

    @JvmStatic
    fun loadReceiptMessageInfoClass(classLoader: ClassLoader): Class<*> {
        val result = bridge.findMethod {
            matcher {
                addUsingString("ReadReceiptUtils/buildReadReceiptHandler malformed")
            }
        }
        if (result.isEmpty()) throw RuntimeException("ReceiptMessageInfo class not found (ReadReceiptUtils method not found)")

        val deviceJid = findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.DeviceJid")
            ?: throw RuntimeException("DeviceJid class not found for ReceiptMessageInfo")

        for (invoke in result[0].invokes) {
            if (invoke.isConstructor && invoke.paramTypeNames.any { it.contains("DeviceJid") }) {
                return invoke.getClassInstance(classLoader)
            }
        }
        throw RuntimeException("ReceiptMessageInfo constructor not found")
    }

    @JvmStatic
    fun loadOndispatchMessage(classLoader: ClassLoader): Array<Method> {
        val result = bridge.findMethod {
            matcher {
                addUsingNumber(419)
                paramCount(1, 3)
            }
        }
        val methods = result
            .filter { !it.paramTypeNames.isEmpty() && it.paramTypeNames[0].contains("Message") }
            .mapNotNull {
                try { it.getMethodInstance(classLoader) } catch (_: Throwable) { null }
            }
            .toTypedArray()
        if (methods.isEmpty()) throw RuntimeException("onDispatchMessage methods not found")
        return methods
    }

    @JvmStatic
    fun loadSenderPlayedClass(classLoader: ClassLoader): Class<*> {
        return findFirstClassUsingStrings(classLoader, "sendmethods/sendClearDirty")
            ?: throw RuntimeException("SenderPlayed class not found")
    }

    @JvmStatic
    fun loadSenderPlayedMethod(classLoader: ClassLoader): Method {
        val clazz = loadSenderPlayedClass(classLoader)
        val abstractMediaMessageClass = loadAbstractMediaMessageClass(classLoader)

        val interfacesList = ArrayList<Class<*>>()
        interfacesList.add(abstractMediaMessageClass)
        interfacesList.addAll(listOf(*abstractMediaMessageClass.interfaces))

        for (method in clazz.methods) {
            if (method.parameterCount != 1) continue
            val parameterType = method.parameterTypes[0]
            for (iface in interfacesList) {
                if (iface.isAssignableFrom(parameterType)) {
                    return method
                }
            }
        }

        // Fallback: find method with 1 param assignable from FMessage class
        val fmessageClass = loadFMessageClass(classLoader)
        for (method in clazz.methods) {
            if (method.parameterCount == 1 && fmessageClass.isAssignableFrom(method.parameterTypes[0])) {
                return method
            }
        }

        throw RuntimeException("SenderPlayed method not found")
    }

    @JvmStatic
    fun loadSenderPlayedBusiness(classLoader: ClassLoader): Method {
        val clazz = loadSenderPlayedClass(classLoader)
        for (method in clazz.methods) {
            if (method.parameterCount > 0 && method.parameterTypes[0] == Set::class.java) {
                return method
            }
        }
        throw RuntimeException("SenderPlayedBusiness method not found")
    }

    @JvmStatic
    fun loadAbstractMediaMessageClass(classLoader: ClassLoader): Class<*> {
        val fMessageClass = loadFMessageClass(classLoader)
        for (str in listOf("first_viewed_timestamp", "Field is set but is null in MediaDataV2")) {
            val classList = bridge.findClass {
                matcher {
                    addUsingString(str)
                }
            }
            for (clazz in classList) {
                val clazzInstance = clazz.getInstance(classLoader)
                if (fMessageClass.isAssignableFrom(clazzInstance)) return clazzInstance
            }
        }
        throw RuntimeException("AbstractMediaMessage class not found")
    }

    // ── MediaQuality DexKit methods ──────────────────────────────────────

    @JvmStatic
    fun loadProcessVideoQualityClass(classLoader: ClassLoader): Class<*> {
        return findFirstClassUsingStrings(classLoader, "ProcessVideoQuality(")
            ?: throw RuntimeException("ProcessVideoQuality class not found")
    }

    @JvmStatic
    fun loadMediaDataVideoConfigurationClass(classLoader: ClassLoader): Class<*> {
        return findFirstClassUsingStrings(classLoader, "MediaDataVideoConfiguration(")
            ?: throw RuntimeException("MediaDataVideoConfiguration class not found")
    }

    @JvmStatic
    fun loadVideoTranscoderStartMethod(classLoader: ClassLoader): Method {
        return findMethodByString(classLoader, "VideoTranscoder/transcodeVideoNew/")
            ?: throw RuntimeException("VideoTranscoder start method not found")
    }

    @JvmStatic
    fun loadMediaQualityVideoMethod2(classLoader: ClassLoader): Method {
        return findMethodByString(classLoader, "getCorrectedResolution")
            ?: throw RuntimeException("MediaQualityVideo method not found")
    }

    @JvmStatic
    fun loadMediaQualityVideoFields(classLoader: ClassLoader): HashMap<String, Field> {
        val method = loadMediaQualityVideoMethod2(classLoader)
        val methodString = method.returnType.getDeclaredMethod("toString")
        val methodData = bridge.getMethodData(methodString) ?: return HashMap()
        val usingFields = methodData.usingFields
        val usingStrings = methodData.usingStrings
        val result = HashMap<String, Field>()
        var idxStrings = 0
        var idxFields = 0
        while (idxStrings < usingStrings.size) {
            if (idxFields == usingFields.size) break
            if (usingStrings[idxStrings] == "outputAspectRatio") {
                idxStrings++
                continue
            }
            val field = usingFields[idxFields].field.getFieldInstance(classLoader)
            result[usingStrings[idxStrings]] = field
            idxStrings++
            idxFields++
        }
        return result
    }

    @JvmStatic
    fun loadMediaQualityOriginalVideoFields(classLoader: ClassLoader): HashMap<String, Field> {
        val method = loadMediaQualityVideoMethod2(classLoader)
        val methodString = try {
            method.parameterTypes[0].getDeclaredMethod("toString")
        } catch (_: Throwable) {
            return HashMap()
        }
        val methodData = bridge.getMethodData(methodString) ?: return HashMap()
        val usingFields = methodData.usingFields
        val usingStrings = methodData.usingStrings
        val result = HashMap<String, Field>()
        for (i in usingStrings.indices) {
            if (i == usingFields.size) break
            val field = usingFields[i].field.getFieldInstance(classLoader)
            result[usingStrings[i]] = field
        }
        return result
    }

    @JvmStatic
    fun loadProcessImageQualityClass(classLoader: ClassLoader): Class<*> {
        val classDataList = bridge.findClass {
            matcher {
                addUsingString("ProcessImageQuality(", StringMatchType.StartsWith)
            }
        }
        if (classDataList.isEmpty()) throw RuntimeException("ProcessImageQuality class not found")
        return classDataList[0].getInstance(classLoader)
    }

    @JvmStatic
    fun loadMediaQualitySelectionMethod(classLoader: ClassLoader): Method {
        var methodData = bridge.findMethod {
            matcher {
                addUsingString("enable_media_quality_tool")
                returnType(Boolean::class.javaPrimitiveType!!)
            }
        }
        if (methodData.isEmpty()) {
            methodData = bridge.findMethod {
                matcher {
                    addUsingString("show_media_quality_toggle")
                    returnType(Boolean::class.javaPrimitiveType!!)
                }
            }
        }
        if (methodData.isEmpty()) throw RuntimeException("MediaQualitySelection method not found")
        return methodData[0].getMethodInstance(classLoader)
    }

    @JvmStatic
    fun loadBottomBarConfigClass(classLoader: ClassLoader): Class<*> {
        return findFirstClassUsingStrings(classLoader, "BottomBarConfig(")
            ?: throw RuntimeException("BottomBarConfig class not found")
    }

    @JvmStatic
    fun getAllMapFields(clazz: Class<*>): HashMap<String, Field> {
        val methodString = try {
            clazz.getDeclaredMethod("toString")
        } catch (_: Throwable) {
            return HashMap()
        }
        val methodData = bridge.getMethodData(methodString) ?: return HashMap()
        val usingFields = methodData.usingFields
        val usingStrings = methodData.usingStrings
        val result = HashMap<String, Field>()
        var idxFields = 0
        for (i in usingStrings.indices) {
            if (idxFields == usingFields.size) break
            val raw = usingStrings[i]
            val string = raw.trim()
            if (string.isEmpty()) continue
            val eq = string.lastIndexOf('=')
            if (eq < 0) continue
            var start = 0
            for (j in eq - 1 downTo 0) {
                val c = string[j]
                if (c == '\'' || c == ',' || c == ' ' || c == '(' || c == ')' || c == ':' || c == '{' || c == '}') {
                    start = j + 1
                    break
                }
            }
            if (start >= eq) continue
            val name = string.substring(start, eq)
            val field = usingFields[idxFields].field.getFieldInstance(clazz.classLoader!!)
            result[name] = field
            idxFields++
        }
        return result
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun findFirstClassUsingName(classLoader: ClassLoader, type: StringMatchType, name: String): Class<*>? {
        val result = bridge.findClass {
            matcher {
                className(name, type)
            }
        }
        if (result.isEmpty()) return null
        return result[0].getInstance(classLoader)
    }
}
