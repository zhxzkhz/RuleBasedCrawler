import com.alibaba.fastjson2.JSONPath
fun main() {
    val json = ""\"{"tags": ["修仙", "系统"], "id": "12345"}""\"
    val res = JSONPath.extract(json, "$['id','tags']")
    println(res)
}
