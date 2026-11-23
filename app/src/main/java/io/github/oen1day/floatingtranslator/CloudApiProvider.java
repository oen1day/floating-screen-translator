package io.github.oen1day.floatingtranslator;

public final class CloudApiProvider {
    public static final Provider CUSTOM = new Provider(
            "custom",
            "自定义 OpenAI 兼容服务",
            "",
            ""
    );

    public static final Provider[] PRESETS = {
            CUSTOM,
            new Provider(
                    "openai",
                    "OpenAI",
                    "https://api.openai.com/v1/chat/completions",
                    "gpt-4o-mini"
            ),
            new Provider(
                    "deepseek",
                    "DeepSeek",
                    "https://api.deepseek.com/chat/completions",
                    "deepseek-chat"
            ),
            new Provider(
                    "dashscope",
                    "阿里云百炼 / 通义",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                    "qwen-plus"
            ),
            new Provider(
                    "moonshot",
                    "Moonshot / Kimi",
                    "https://api.moonshot.cn/v1/chat/completions",
                    "moonshot-v1-8k"
            ),
            new Provider(
                    "zhipu",
                    "智谱 GLM",
                    "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                    "glm-4-flash"
            ),
            new Provider(
                    "openrouter",
                    "OpenRouter",
                    "https://openrouter.ai/api/v1/chat/completions",
                    "openai/gpt-4o-mini"
            )
    };

    private CloudApiProvider() {
    }

    public static Provider findById(String id) {
        if (id != null) {
            for (Provider provider : PRESETS) {
                if (provider.id.equals(id)) {
                    return provider;
                }
            }
        }
        return CUSTOM;
    }

    public static int indexOf(String id) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].id.equals(id)) {
                return i;
            }
        }
        return 0;
    }

    public static Provider detectFromApiKey(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        if (value.startsWith("sk-or-v1-")) {
            return findById("openrouter");
        }
        if (value.startsWith("sk-proj-")) {
            return findById("openai");
        }
        return CUSTOM;
    }

    public static final class Provider {
        public final String id;
        public final String label;
        public final String endpoint;
        public final String model;

        Provider(String id, String label, String endpoint, String model) {
            this.id = id;
            this.label = label;
            this.endpoint = endpoint;
            this.model = model;
        }

        public boolean isCustom() {
            return this == CUSTOM || "custom".equals(id);
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
