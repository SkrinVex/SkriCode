#include <jni.h>
#include <string>
#include <sstream>
#include <thread>
#include <chrono>
#include <map>
#include <cmath>

struct Block {
    std::string type;
    bool is_async = false;
    std::map<std::string, std::string> params;

    std::string get(const std::string& key, const std::string& def = "") const {
        auto it = params.find(key);
        return it != params.end() ? it->second : def;
    }
};

// --- Minimal JSON parser ---
static void skip_ws(const std::string& s, size_t& p) {
    while (p < s.size() && (s[p]==' '||s[p]=='\n'||s[p]=='\r'||s[p]=='\t')) p++;
}

static std::string read_string(const std::string& s, size_t& p) {
    skip_ws(s, p);
    if (p >= s.size() || s[p] != '"') return "";
    p++;
    std::string r;
    while (p < s.size() && s[p] != '"') {
        if (s[p] == '\\') { p++; if (p < s.size()) r += s[p++]; }
        else r += s[p++];
    }
    if (p < s.size()) p++; // closing "
    return r;
}

static std::string read_value(const std::string& s, size_t& p) {
    skip_ws(s, p);
    if (p < s.size() && s[p] == '"') return read_string(s, p);
    std::string v;
    while (p < s.size() && s[p]!=',' && s[p]!='}' && s[p]!=']') v += s[p++];
    while (!v.empty() && (v.back()==' '||v.back()=='\n')) v.pop_back();
    return v;
}

static Block parse_block(const std::string& s, size_t& p) {
    Block b;
    while (p < s.size() && s[p] != '}') {
        skip_ws(s, p);
        if (s[p] == '"') {
            std::string key = read_string(s, p);
            skip_ws(s, p);
            if (p < s.size() && s[p] == ':') p++;
            skip_ws(s, p);
            if (key == "type") {
                b.type = read_string(s, p);
            } else if (key == "async") {
                b.is_async = read_value(s, p) == "true";
            } else if (key == "params") {
                skip_ws(s, p);
                if (p < s.size() && s[p] == '{') p++;
                while (p < s.size() && s[p] != '}') {
                    skip_ws(s, p);
                    if (s[p] == '"') {
                        std::string pk = read_string(s, p);
                        skip_ws(s, p);
                        if (p < s.size() && s[p] == ':') p++;
                        skip_ws(s, p);
                        b.params[pk] = read_string(s, p);
                    } else p++;
                }
                if (p < s.size()) p++; // skip '}'
            } else {
                read_value(s, p);
            }
        } else p++;
    }
    if (p < s.size()) p++; // skip '}'
    return b;
}

// --- Execution context (variables) ---
static std::map<std::string, std::string> g_vars;

static double to_num(const std::string& s) {
    try { return std::stod(s); } catch (...) { return 0; }
}

static std::string fmt(double v) {
    if (v == std::floor(v) && std::abs(v) < 1e15)
        return std::to_string((long long)v);
    std::ostringstream ss;
    ss << v;
    return ss.str();
}

static std::string resolve(const std::string& val) {
    // If value looks like a var name and exists — return it
    if (!val.empty() && g_vars.count(val)) return g_vars[val];
    return val;
}

static std::string execute_block(const Block& b) {
    std::ostringstream out;

    if (b.type == "print") {
        out << "[print] " << resolve(b.get("message")) << "\n";

    } else if (b.type == "delay") {
        int ms = (int)to_num(b.get("ms", "0"));
        ms = std::max(0, std::min(ms, 10000));
        out << "[delay] ⏱ " << ms << " мс...\n";
        std::this_thread::sleep_for(std::chrono::milliseconds(ms));
        out << "[delay] ✓ готово\n";

    } else if (b.type == "math") {
        double a = to_num(resolve(b.get("a", "0")));
        double bv = to_num(resolve(b.get("b", "0")));
        std::string op = b.get("op", "+");
        double result = 0;
        if (op == "+") result = a + bv;
        else if (op == "-") result = a - bv;
        else if (op == "*") result = a * bv;
        else if (op == "/") result = bv != 0 ? a / bv : 0;
        else if (op == "%") result = bv != 0 ? std::fmod(a, bv) : 0;
        out << "[math] " << fmt(a) << " " << op << " " << fmt(bv) << " = " << fmt(result) << "\n";

    } else if (b.type == "if") {
        double a = to_num(resolve(b.get("a", "0")));
        double bv = to_num(resolve(b.get("b", "0")));
        std::string op = b.get("op", "==");
        bool cond = false;
        if (op == "==") cond = a == bv;
        else if (op == "!=") cond = a != bv;
        else if (op == ">")  cond = a > bv;
        else if (op == "<")  cond = a < bv;
        else if (op == ">=") cond = a >= bv;
        else if (op == "<=") cond = a <= bv;
        out << "[if] " << fmt(a) << " " << op << " " << fmt(bv)
            << " → " << (cond ? "истина" : "ложь") << "\n";
        out << "[if] " << (cond ? b.get("then") : b.get("else")) << "\n";

    } else if (b.type == "repeat") {
        int count = (int)to_num(b.get("count", "1"));
        count = std::max(0, std::min(count, 100));
        std::string msg = b.get("message");
        for (int i = 0; i < count; i++)
            out << "[repeat] " << i+1 << ": " << msg << "\n";

    } else if (b.type == "set_var") {
        std::string name = b.get("name");
        std::string val  = b.get("value");
        if (!name.empty()) {
            g_vars[name] = val;
            out << "[var] " << name << " = " << val << "\n";
        }

    } else if (b.type == "print_var") {
        std::string name = b.get("name");
        auto it = g_vars.find(name);
        if (it != g_vars.end())
            out << "[var] " << name << " = " << it->second << "\n";
        else
            out << "[var] ⚠ переменная '" << name << "' не найдена\n";

    } else if (b.type == "str_join") {
        std::string a = resolve(b.get("a"));
        std::string bv = resolve(b.get("b"));
        out << "[str] " << a << bv << "\n";

    } else {
        out << "[?] неизвестный блок: " << b.type << "\n";
    }

    return out.str();
}

extern "C" JNIEXPORT jstring JNICALL
Java_su_SkrinVex_SkriPts_engine_ScriptEngine_nativeExecute(
        JNIEnv* env, jobject, jstring blocksJson) {

    const char* raw = env->GetStringUTFChars(blocksJson, nullptr);
    std::string json(raw);
    env->ReleaseStringUTFChars(blocksJson, raw);

    g_vars.clear(); // сброс переменных перед каждым запуском

    std::ostringstream log;
    log << "[engine] ▶ запуск\n";

    size_t p = 0;
    while (p < json.size() && json[p] != '[') p++;
    p++;

    int idx = 0;
    while (p < json.size() && json[p] != ']') {
        skip_ws(json, p);
        if (p < json.size() && json[p] == '{') {
            p++;
            Block b = parse_block(json, p);
            log << execute_block(b);
            idx++;
        } else p++;
    }

    log << "[engine] ■ выполнено блоков: " << idx << "\n";
    return env->NewStringUTF(log.str().c_str());
}
