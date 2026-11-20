#include "stadium_dsp/neural_clone_engine.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace stadium_dsp {

namespace {

// Minimal self-contained clean-room JSON DOM parser (zero third-party dependencies)
struct JsonValue {
    enum class Type { kNull, kBool, kNumber, kString, kArray, kObject };
    Type type = Type::kNull;
    bool bool_val = false;
    double num_val = 0.0;
    std::string str_val;
    std::vector<JsonValue> arr_val;
    std::map<std::string, JsonValue> obj_val;

    const JsonValue* Get(const std::string& key) const {
        if (type != Type::kObject) return nullptr;
        auto it = obj_val.find(key);
        return (it == obj_val.end()) ? nullptr : &it->second;
    }
    std::string AsString(const std::string& def = "") const {
        return (type == Type::kString) ? str_val : def;
    }
    double AsNumber(double def = 0.0) const {
        return (type == Type::kNumber) ? num_val : def;
    }
    bool AsBool(bool def = false) const {
        if (type == Type::kBool) return bool_val;
        if (type == Type::kNumber) return num_val != 0.0;
        return def;
    }
};

class JsonParser {
public:
    JsonParser(const char* s, size_t n) : s_(s), n_(n), p_(0) {}

    bool Parse(JsonValue& out) {
        SkipWs();
        if (!ParseValue(out)) return false;
        SkipWs();
        return p_ == n_;
    }

private:
    void SkipWs() {
        while (p_ < n_ && std::isspace(static_cast<unsigned char>(s_[p_]))) ++p_;
    }

    bool ParseValue(JsonValue& v) {
        SkipWs();
        if (p_ >= n_) return false;
        const char c = s_[p_];
        if (c == '{') return ParseObject(v);
        if (c == '[') return ParseArray(v);
        if (c == '"') {
            v.type = JsonValue::Type::kString;
            return ParseString(v.str_val);
        }
        if (c == 't' && p_ + 4 <= n_ && std::memcmp(s_ + p_, "true", 4) == 0) {
            p_ += 4;
            v.type = JsonValue::Type::kBool;
            v.bool_val = true;
            return true;
        }
        if (c == 'f' && p_ + 5 <= n_ && std::memcmp(s_ + p_, "false", 5) == 0) {
            p_ += 5;
            v.type = JsonValue::Type::kBool;
            v.bool_val = false;
            return true;
        }
        if (c == 'n' && p_ + 4 <= n_ && std::memcmp(s_ + p_, "null", 4) == 0) {
            p_ += 4;
            v.type = JsonValue::Type::kNull;
            return true;
        }
        return ParseNumber(v);
    }

    bool ParseString(std::string& out) {
        if (p_ >= n_ || s_[p_] != '"') return false;
        ++p_;
        out.clear();
        while (p_ < n_) {
            const char c = s_[p_++];
            if (c == '"') return true;
            if (c == '\\') {
                if (p_ >= n_) return false;
                const char e = s_[p_++];
                switch (e) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u':
                        if (p_ + 4 > n_) return false;
                        p_ += 4;
                        out.push_back('?');
                        break;
                    default: out.push_back(e); break;
                }
            } else {
                out.push_back(c);
            }
        }
        return false;
    }

    bool ParseNumber(JsonValue& v) {
        const size_t start = p_;
        if (p_ < n_ && (s_[p_] == '-' || s_[p_] == '+')) ++p_;
        bool has_digit = false;
        while (p_ < n_ && std::isdigit(static_cast<unsigned char>(s_[p_]))) {
            has_digit = true;
            ++p_;
        }
        if (p_ < n_ && s_[p_] == '.') {
            ++p_;
            while (p_ < n_ && std::isdigit(static_cast<unsigned char>(s_[p_]))) {
                has_digit = true;
                ++p_;
            }
        }
        if (!has_digit) return false;
        if (p_ < n_ && (s_[p_] == 'e' || s_[p_] == 'E')) {
            ++p_;
            if (p_ < n_ && (s_[p_] == '-' || s_[p_] == '+')) ++p_;
            while (p_ < n_ && std::isdigit(static_cast<unsigned char>(s_[p_]))) ++p_;
        }
        std::string token(s_ + start, p_ - start);
        v.type = JsonValue::Type::kNumber;
        v.num_val = std::strtod(token.c_str(), nullptr);
        return true;
    }

    bool ParseArray(JsonValue& v) {
        if (p_ >= n_ || s_[p_] != '[') return false;
        ++p_;
        v.type = JsonValue::Type::kArray;
        SkipWs();
        if (p_ < n_ && s_[p_] == ']') {
            ++p_;
            return true;
        }
        while (p_ < n_) {
            JsonValue elem;
            if (!ParseValue(elem)) return false;
            v.arr_val.push_back(std::move(elem));
            SkipWs();
            if (p_ < n_ && s_[p_] == ',') {
                ++p_;
                continue;
            }
            if (p_ < n_ && s_[p_] == ']') {
                ++p_;
                return true;
            }
            return false;
        }
        return false;
    }

    bool ParseObject(JsonValue& v) {
        if (p_ >= n_ || s_[p_] != '{') return false;
        ++p_;
        v.type = JsonValue::Type::kObject;
        SkipWs();
        if (p_ < n_ && s_[p_] == '}') {
            ++p_;
            return true;
        }
        while (p_ < n_) {
            SkipWs();
            std::string key;
            if (!ParseString(key)) return false;
            SkipWs();
            if (p_ >= n_ || s_[p_] != ':') return false;
            ++p_;
            JsonValue val;
            if (!ParseValue(val)) return false;
            v.obj_val.emplace(std::move(key), std::move(val));
            SkipWs();
            if (p_ < n_ && s_[p_] == ',') {
                ++p_;
                continue;
            }
            if (p_ < n_ && s_[p_] == '}') {
                ++p_;
                return true;
            }
            return false;
        }
        return false;
    }

    const char* s_;
    size_t n_;
    size_t p_;
};

void FlattenNumbers(const JsonValue& v, std::vector<float>& out) {
    if (v.type == JsonValue::Type::kNumber) {
        out.push_back(static_cast<float>(v.num_val));
    } else if (v.type == JsonValue::Type::kArray) {
        for (const auto& c : v.arr_val) FlattenNumbers(c, out);
    }
}

uint16_t ReadU16LE(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

uint32_t ReadU32LE(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

// 4-Term Blackman-Harris Windowed-Sinc Resampler to 48,000 Hz (2,048 taps)
void ResampleBlackmanHarrisSinc(const std::vector<double>& src, uint32_t src_sr,
                                std::array<float, 2048>& dst_48k) {
    dst_48k.fill(0.0f);
    if (src.empty() || src_sr == 0) return;

    if (src_sr == 48000u) {
        const size_t n = std::min(src.size(), dst_48k.size());
        for (size_t i = 0; i < n; ++i) dst_48k[i] = static_cast<float>(src[i]);
        return;
    }

    const double ratio = static_cast<double>(src_sr) / 48000.0;  // source samples per output sample
    const double cutoff = std::min(1.0, 1.0 / ratio) * 0.96;
    constexpr int kHalfWidth = 24;

    for (size_t n = 0; n < dst_48k.size(); ++n) {
        const double center = static_cast<double>(n) * ratio;
        const int i_center = static_cast<int>(std::floor(center));
        double acc = 0.0;
        for (int k = i_center - kHalfWidth; k <= i_center + kHalfWidth; ++k) {
            if (k < 0 || static_cast<size_t>(k) >= src.size()) continue;
            const double d = center - static_cast<double>(k);
            const double u = (d + static_cast<double>(kHalfWidth)) / (2.0 * static_cast<double>(kHalfWidth));
            if (u < 0.0 || u > 1.0) continue;
            // 4-Term Blackman-Harris window (-92 dB sidelobe attenuation)
            const double win = 0.35875 - 0.48829 * std::cos(kTwoPiD * u) +
                               0.14128 * std::cos(2.0 * kTwoPiD * u) -
                               0.01168 * std::cos(3.0 * kTwoPiD * u);
            const double x = kPiD * cutoff * d;
            const double sinc = (std::fabs(x) < 1.0e-9) ? 1.0 : (std::sin(x) / x);
            acc += src[static_cast<size_t>(k)] * cutoff * sinc * win;
        }
        dst_48k[n] = static_cast<float>(acc);
    }
}

// Extract cabinet cone resonance Peak1 (45..220 Hz) via high-resolution DFT scan
ReactiveCabImpedanceDesc ExtractReactiveImpedanceFromIr(const std::array<float, 2048>& ir) {
    ReactiveCabImpedanceDesc z{};
    double best_f = 96.0;
    double best_mag = -1.0;
    for (double f = 45.0; f <= 220.0; f += 2.0) {
        const double w = kTwoPiD * f / kSampleRateD;
        double re = 0.0, im = 0.0;
        for (size_t n = 0; n < ir.size(); ++n) {
            const double ang = w * static_cast<double>(n);
            re += static_cast<double>(ir[n]) * std::cos(ang);
            im -= static_cast<double>(ir[n]) * std::sin(ang);
        }
        const double mag = std::sqrt(re * re + im * im);
        if (mag > best_mag) {
            best_mag = mag;
            best_f = f;
        }
    }
    z.peak1_hz = static_cast<float>(best_f);
    z.peak2_hz = static_cast<float>(std::min(360.0, best_f * 2.05));
    return z;
}

}  // namespace

bool LoadExternalCabIrWavFromMemory(const uint8_t* data, size_t size,
                                    DecodedIrAsset* out_ir, std::string* out_err) {
    if (!data || !out_ir || size < 44) {
        if (out_err) *out_err = "WAV buffer too small or null";
        return false;
    }
    if (std::memcmp(data, "RIFF", 4) != 0 || std::memcmp(data + 8, "WAVE", 4) != 0) {
        if (out_err) *out_err = "Missing RIFF/WAVE header";
        return false;
    }

    uint16_t audio_format = 0;
    uint16_t channels = 0;
    uint32_t sample_rate = 0;
    uint16_t bits_per_sample = 0;
    const uint8_t* pcm_data = nullptr;
    uint32_t pcm_size = 0;

    size_t pos = 12;
    while (pos + 8 <= size) {
        const uint8_t* chunk_id = data + pos;
        const uint32_t chunk_size = ReadU32LE(data + pos + 4);
        pos += 8;
        if (pos + chunk_size > size) break;

        if (std::memcmp(chunk_id, "fmt ", 4) == 0 && chunk_size >= 16) {
            audio_format = ReadU16LE(data + pos);
            channels = ReadU16LE(data + pos + 2);
            sample_rate = ReadU32LE(data + pos + 4);
            bits_per_sample = ReadU16LE(data + pos + 14);
            // Handle WAVE_FORMAT_EXTENSIBLE (0xFFFE)
            if (audio_format == 0xFFFEu && chunk_size >= 40) {
                audio_format = ReadU16LE(data + pos + 24);
            }
        } else if (std::memcmp(chunk_id, "data", 4) == 0) {
            pcm_data = data + pos;
            pcm_size = chunk_size;
        }
        pos += chunk_size + (chunk_size & 1u);
    }

    if (!pcm_data || channels == 0 || sample_rate < 8000 || sample_rate > 384000) {
        if (out_err) *out_err = "Invalid WAV format or missing data chunk";
        return false;
    }

    const size_t bytes_per_sample = bits_per_sample / 8;
    const size_t frame_bytes = bytes_per_sample * channels;
    if (frame_bytes == 0) {
        if (out_err) *out_err = "Unsupported bits per sample";
        return false;
    }

    const size_t num_frames = pcm_size / frame_bytes;
    std::vector<double> mono(num_frames, 0.0);

    for (size_t f = 0; f < num_frames; ++f) {
        double sum = 0.0;
        for (uint16_t ch = 0; ch < channels; ++ch) {
            const uint8_t* sp = pcm_data + f * frame_bytes + ch * bytes_per_sample;
            double v = 0.0;
            if (audio_format == 1 && bits_per_sample == 16) {
                int16_t iv = static_cast<int16_t>(ReadU16LE(sp));
                v = static_cast<double>(iv) / 32768.0;
            } else if (audio_format == 1 && bits_per_sample == 24) {
                int32_t iv = static_cast<int32_t>(sp[0]) | (static_cast<int32_t>(sp[1]) << 8) |
                             (static_cast<int32_t>(sp[2]) << 16);
                if (iv & 0x00800000) iv |= 0xFF000000;
                v = static_cast<double>(iv) / 8388608.0;
            } else if (audio_format == 1 && bits_per_sample == 32) {
                int32_t iv = static_cast<int32_t>(ReadU32LE(sp));
                v = static_cast<double>(iv) / 2147483648.0;
            } else if (audio_format == 3 && bits_per_sample == 32) {
                float fv = 0.0f;
                std::memcpy(&fv, sp, 4);
                v = static_cast<double>(fv);
            } else if (audio_format == 3 && bits_per_sample == 64) {
                double dv = 0.0;
                std::memcpy(&dv, sp, 8);
                v = dv;
            } else {
                if (out_err) *out_err = "Unsupported WAV sample encoding";
                return false;
            }
            sum += v;
        }
        mono[f] = sum / static_cast<double>(channels);
    }

    ResampleBlackmanHarrisSinc(mono, sample_rate, out_ir->ir_48k);
    out_ir->source_sample_rate = sample_rate;
    out_ir->source_bits_per_sample = bits_per_sample;
    out_ir->extracted_z = ExtractReactiveImpedanceFromIr(out_ir->ir_48k);
    return true;
}

bool LoadExternalNamJsonFromMemory(const char* json_text, size_t len,
                                   NeuralCloneEngine* out_engine, std::string* out_err) {
    if (!json_text || !out_engine || len == 0) {
        if (out_err) *out_err = "Empty .nam JSON input";
        return false;
    }
    JsonValue root;
    JsonParser parser(json_text, len);
    if (!parser.Parse(root) || root.type != JsonValue::Type::kObject) {
        if (out_err) *out_err = "Invalid .nam JSON syntax";
        return false;
    }

    CloneMetadata meta;
    meta.architecture = root.Get("architecture") ? root.Get("architecture")->AsString("WaveNet") : "WaveNet";
    if (const auto* sr = root.Get("sample_rate")) meta.sample_rate = static_cast<float>(sr->AsNumber(48000.0));
    if (const auto* m = root.Get("metadata")) {
        if (const auto* b = m->Get("brand")) meta.brand = b->AsString(meta.brand);
        if (const auto* nm = m->Get("model")) meta.product = nm->AsString(meta.product);
        if (const auto* nm2 = m->Get("name")) if (meta.product == "Built-In Reference Clone") meta.product = nm2->AsString(meta.product);
        if (const auto* nt = m->Get("notes")) meta.notes = nt->AsString(meta.notes);
        if (const auto* id = m->Get("uuid")) meta.uuid = id->AsString(meta.uuid);
        if (const auto* in_dbu = m->Get("input_level_dbu")) meta.input_level_dbu = static_cast<float>(in_dbu->AsNumber(12.0));
        if (const auto* out_dbu = m->Get("output_level_dbu")) meta.output_level_dbu = static_cast<float>(out_dbu->AsNumber(meta.input_level_dbu));
    }

    const auto* cfg = root.Get("config");
    const auto* weights_v = root.Get("weights");
    if (!cfg || !weights_v || weights_v->type != JsonValue::Type::kArray) {
        if (out_err) *out_err = "Missing config or weights array in .nam";
        return false;
    }
    std::vector<float> weights;
    FlattenNumbers(*weights_v, weights);
    size_t w_idx = 0;

    auto next_w = [&](std::vector<float>& dst, size_t count) -> bool {
        if (w_idx + count > weights.size()) return false;
        dst.assign(weights.begin() + static_cast<ptrdiff_t>(w_idx),
                   weights.begin() + static_cast<ptrdiff_t>(w_idx + count));
        w_idx += count;
        return true;
    };

    const auto* layers_cfg = cfg->Get("layers");
    if (!layers_cfg || layers_cfg->type != JsonValue::Type::kArray || layers_cfg->arr_val.empty()) {
        if (out_err) *out_err = "Missing WaveNet layers in .nam config";
        return false;
    }

    std::vector<WaveNetStage> stages;
    for (const auto& lc : layers_cfg->arr_val) {
        WaveNetStage st;
        st.input_size = static_cast<int>(lc.Get("input_size") ? lc.Get("input_size")->AsNumber(1) : 1);
        st.condition_size = static_cast<int>(lc.Get("condition_size") ? lc.Get("condition_size")->AsNumber(1) : 1);
        st.channels = static_cast<int>(lc.Get("channels") ? lc.Get("channels")->AsNumber(1) : 1);
        st.head_size = static_cast<int>(lc.Get("head_size") ? lc.Get("head_size")->AsNumber(1) : 1);
        const int kernel_size = static_cast<int>(lc.Get("kernel_size") ? lc.Get("kernel_size")->AsNumber(2) : 2);
        const bool gated = lc.Get("gated") ? lc.Get("gated")->AsBool(false) : false;
        st.head_bias = lc.Get("head_bias") ? lc.Get("head_bias")->AsBool(false) : false;

        if (!next_w(st.input_w, static_cast<size_t>(st.channels * st.input_size))) {
            if (out_err) *out_err = "Truncated .nam weights at stage input_w";
            return false;
        }

        const auto* dils = lc.Get("dilations");
        if (!dils || dils->type != JsonValue::Type::kArray) {
            if (out_err) *out_err = "Missing dilations array in .nam layer";
            return false;
        }

        const int out_c = gated ? (2 * st.channels) : st.channels;
        for (const auto& dv : dils->arr_val) {
            WaveNetLayer layer;
            layer.channels = st.channels;
            layer.condition_size = st.condition_size;
            layer.kernel_size = kernel_size;
            layer.dilation = std::max(1, static_cast<int>(dv.AsNumber(1)));
            layer.gated = gated;
            if (!next_w(layer.conv_w, static_cast<size_t>(out_c * st.channels * kernel_size)) ||
                !next_w(layer.conv_b, static_cast<size_t>(out_c)) ||
                !next_w(layer.mixin_w, static_cast<size_t>(out_c * st.condition_size)) ||
                !next_w(layer.one_x_one_w, static_cast<size_t>(st.channels * st.channels)) ||
                !next_w(layer.one_x_one_b, static_cast<size_t>(st.channels))) {
                if (out_err) *out_err = "Truncated .nam weights inside WaveNet layer";
                return false;
            }
            st.layers.push_back(std::move(layer));
        }

        // Head rechannel: [head_size x channels] (+ optional bias)
        std::vector<float> head_rc;
        if (!next_w(head_rc, static_cast<size_t>(st.head_size * st.channels))) {
            if (out_err) *out_err = "Truncated .nam weights at head_rechannel";
            return false;
        }
        // If head_size > 1 (Tone3000 style final head projection), collapse final 1x1 weights when present
        if (st.head_size > 1 && w_idx + static_cast<size_t>(st.head_size) <= weights.size()) {
            std::vector<float> final_head;
            next_w(final_head, static_cast<size_t>(st.head_size));
            st.head_rechannel_w.assign(static_cast<size_t>(st.channels), 0.0f);
            for (int h = 0; h < st.head_size; ++h) {
                for (int c = 0; c < st.channels; ++c) {
                    st.head_rechannel_w[static_cast<size_t>(c)] +=
                        final_head[static_cast<size_t>(h)] * head_rc[static_cast<size_t>(h * st.channels + c)];
                }
            }
        } else {
            st.head_rechannel_w = std::move(head_rc);
        }
        if (st.head_bias) {
            if (!next_w(st.head_rechannel_b, 1)) st.head_rechannel_b = {0.0f};
        }
        stages.push_back(std::move(st));
    }

    const float head_scale = static_cast<float>(cfg->Get("head_scale") ? cfg->Get("head_scale")->AsNumber(1.0) : 1.0);
    out_engine->SetWaveNetModel(std::move(stages), head_scale, meta);
    return true;
}

bool LoadExternalAidaxJsonFromMemory(const char* json_text, size_t len,
                                     NeuralCloneEngine* out_engine, std::string* out_err) {
    if (!json_text || !out_engine || len == 0) {
        if (out_err) *out_err = "Empty .aidax JSON input";
        return false;
    }
    JsonValue root;
    JsonParser parser(json_text, len);
    if (!parser.Parse(root) || root.type != JsonValue::Type::kObject) {
        if (out_err) *out_err = "Invalid .aidax JSON syntax";
        return false;
    }

    CloneMetadata meta;
    meta.architecture = "GRU";
    if (const auto* author = root.Get("author")) meta.brand = author->AsString(meta.brand);
    if (const auto* name = root.Get("name")) meta.product = name->AsString(meta.product);
    if (const auto* desc = root.Get("description")) meta.notes = desc->AsString(meta.notes);

    // Path 1: Standard AIDA-X PyTorch state_dict layout
    const auto* sd = root.Get("state_dict");
    const auto* md = root.Get("model_data");
    if (sd && sd->type == JsonValue::Type::kObject) {
        GruModel g;
        g.hidden_size = static_cast<int>(md && md->Get("hidden_size") ? md->Get("hidden_size")->AsNumber(16) : 16);
        g.input_skip = md && md->Get("input_skip") ? md->Get("input_skip")->AsBool(true) : true;

        const auto* w_ih = sd->Get("rec.weight_ih_l0");
        const auto* w_hh = sd->Get("rec.weight_hh_l0");
        const auto* b_ih = sd->Get("rec.bias_ih_l0");
        const auto* b_hh = sd->Get("rec.bias_hh_l0");
        const auto* lin_w = sd->Get("lin.weight");
        const auto* lin_b = sd->Get("lin.bias");
        if (!w_ih || !w_hh || !b_ih || !b_hh || !lin_w || !lin_b) {
            if (out_err) *out_err = "Missing GRU state_dict tensors in .aidax";
            return false;
        }
        FlattenNumbers(*w_ih, g.w_ih);
        FlattenNumbers(*w_hh, g.w_hh);
        FlattenNumbers(*b_ih, g.b_ih);
        FlattenNumbers(*b_hh, g.b_hh);
        FlattenNumbers(*lin_w, g.lin_w);
        std::vector<float> lb;
        FlattenNumbers(*lin_b, lb);
        g.lin_b = lb.empty() ? 0.0f : lb[0];

        if (static_cast<int>(g.w_ih.size()) != 3 * g.hidden_size ||
            static_cast<int>(g.w_hh.size()) != 3 * g.hidden_size * g.hidden_size ||
            static_cast<int>(g.lin_w.size()) != g.hidden_size) {
            if (out_err) *out_err = "Mismatched GRU tensor dimensions in .aidax";
            return false;
        }
        out_engine->SetGruModel(std::move(g), meta);
        return true;
    }

    // Path 2: Keras sequential layers layout (InputLayer -> GRU -> Dense)
    const auto* layers = root.Get("layers");
    if (layers && layers->type == JsonValue::Type::kArray) {
        GruModel g;
        g.input_skip = true;
        bool found_gru = false;
        bool found_dense = false;
        for (const auto& l : layers->arr_val) {
            const std::string ltype = l.Get("type") ? l.Get("type")->AsString() : "";
            if (ltype == "InputLayer") continue;
            const auto* wv = l.Get("weights");
            if (!wv || wv->type != JsonValue::Type::kArray) continue;
            if (ltype == "gru" || ltype == "GRU") {
                if (wv->arr_val.size() < 3) continue;
                std::vector<float> k_ih, k_hh, k_b;
                FlattenNumbers(wv->arr_val[0], k_ih);
                FlattenNumbers(wv->arr_val[1], k_hh);
                FlattenNumbers(wv->arr_val[2], k_b);
                g.hidden_size = static_cast<int>(k_ih.size()) / 3;
                if (g.hidden_size <= 0) continue;
                const int H = g.hidden_size;
                // Keras gate order is [z, r, n] -> reorder to [r, z, n]
                g.w_ih.resize(3 * H);
                g.b_ih.resize(3 * H);
                g.b_hh.assign(3 * H, 0.0f);
                g.w_hh.resize(3 * H * H);
                for (int i = 0; i < H; ++i) {
                    g.w_ih[i] = k_ih[H + i];
                    g.w_ih[H + i] = k_ih[i];
                    g.w_ih[2 * H + i] = k_ih[2 * H + i];
                    g.b_ih[i] = k_b[H + i];
                    g.b_ih[H + i] = k_b[i];
                    g.b_ih[2 * H + i] = k_b[2 * H + i];
                    if (static_cast<int>(k_b.size()) >= 6 * H) {
                        g.b_hh[i] = k_b[3 * H + H + i];
                        g.b_hh[H + i] = k_b[3 * H + i];
                        g.b_hh[2 * H + i] = k_b[3 * H + 2 * H + i];
                    }
                    for (int j = 0; j < H; ++j) {
                        // Keras recurrent kernel is [H x 3H], transpose & swap z/r
                        g.w_hh[i * H + j] = k_hh[j * 3 * H + H + i];
                        g.w_hh[(H + i) * H + j] = k_hh[j * 3 * H + i];
                        g.w_hh[(2 * H + i) * H + j] = k_hh[j * 3 * H + 2 * H + i];
                    }
                }
                found_gru = true;
            } else if (ltype == "dense" || ltype == "Dense") {
                if (!wv->arr_val.empty()) FlattenNumbers(wv->arr_val[0], g.lin_w);
                if (wv->arr_val.size() >= 2) {
                    std::vector<float> lb;
                    FlattenNumbers(wv->arr_val[1], lb);
                    g.lin_b = lb.empty() ? 0.0f : lb[0];
                }
                found_dense = true;
            }
        }
        if (found_gru && found_dense) {
            out_engine->SetGruModel(std::move(g), meta);
            return true;
        }
    }

    if (out_err) *out_err = "Unrecognized .aidax model layout";
    return false;
}

}  // namespace stadium_dsp
