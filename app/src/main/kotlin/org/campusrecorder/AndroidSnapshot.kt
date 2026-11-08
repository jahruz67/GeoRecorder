package org.campusrecorder

import org.campusrecorder.core.field
import org.campusrecorder.proto.Recording.Field
import org.campusrecorder.proto.Recording.Group
import java.lang.reflect.Modifier

/** Preserve public, zero-argument GNSS getters, including vendor/API optional fields.
 * hasX=false suppresses getX, preserving nullability. Integer nanoseconds remain int64.
 * Reflection is limited to framework GNSS values, never application/device identity APIs. */
object AndroidSnapshot {
    data class Result(val fields: List<Field>,val groups: List<Group>)
    fun read(value: Any, prefix: String="", ignoreGetters: Set<String> = emptySet()): Result {
        val fields=mutableListOf<Field>(); val groups=mutableListOf<Group>()
        val methods=value.javaClass.methods.filter { Modifier.isPublic(it.modifiers) && it.parameterCount==0 &&
            it.name !in ignoreGetters && it.name!in setOf("getClass","getElapsedRealtimeAgeMillis","getElapsedRealtimeAgeNanos") &&
            (it.name.startsWith("get") || it.name.startsWith("has") || it.name.startsWith("is")) }.sortedBy { it.name }
        for(method in methods) {
            val name=method.name
            try {
                if(!name.startsWith("has")) {
                    val suffix=if(name.startsWith("get")) name.removePrefix("get") else name.replaceFirstChar { it.uppercase() }
                    val has=methods.firstOrNull { it.name=="has"+suffix }
                    if(has!=null && has.invoke(value)==false) continue
                }
                val result=method.invoke(value) ?: continue
                when(result) {
                    is Number,is Boolean,is String,is ByteArray -> fields.add(field(name,result))
                    is IntArray -> result.forEachIndexed { i,v -> fields.add(field("$name[$i]",v)) }
                    is LongArray -> result.forEachIndexed { i,v -> fields.add(field("$name[$i]",v)) }
                    is DoubleArray -> result.forEachIndexed { i,v -> fields.add(field("$name[$i]",v)) }
                    is FloatArray -> result.forEachIndexed { i,v -> fields.add(field("$name[$i]",v)) }
                    is android.os.Bundle -> result.keySet().sorted().forEach { key ->
                        val item=result.get(key)
                        when(item) {
                            is Number,is Boolean,is String,is ByteArray -> fields.add(field("$name/$key",item))
                            is IntArray -> item.forEachIndexed { i,v -> fields.add(field("$name/$key[$i]",v)) }
                            is LongArray -> item.forEachIndexed { i,v -> fields.add(field("$name/$key[$i]",v)) }
                            is FloatArray -> item.forEachIndexed { i,v -> fields.add(field("$name/$key[$i]",v)) }
                            is DoubleArray -> item.forEachIndexed { i,v -> fields.add(field("$name/$key[$i]",v)) }
                            null -> Unit
                            else -> fields.add(field("$name/${key}_unsupported_type",item.javaClass.name))
                        }
                    }
                    is Collection<*> -> result.filterNotNull().forEachIndexed { i,v ->
                        if(v.javaClass.name.startsWith("android.location.")) {
                            val nested=read(v,"$prefix$name[$i]/")
                            groups.add(Group.newBuilder().setName("$prefix$name[$i]").addAllFields(nested.fields).build())
                            groups.addAll(nested.groups)
                        }
                    }
                    else -> if(result.javaClass.name.startsWith("android.location.") || result.javaClass.name.startsWith("android.net.wifi.rtt.")) {
                        val nested=read(result,"$prefix$name/")
                        groups.add(Group.newBuilder().setName("$prefix$name").addAllFields(nested.fields).build()); groups.addAll(nested.groups)
                    }
                }
            } catch(e: Exception) { fields.add(field("${name}_read_error",e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName)) }
        }
        return Result(fields,groups)
    }
}
