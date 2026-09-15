"""Audited provider endpoints and model capabilities; selected only by the host."""

from dataclasses import dataclass
from urllib.parse import urlsplit, urlunsplit
import re


DEFAULT_PROVIDER = "xiaomi-mimo"


@dataclass(frozen=True)
class ProviderProfile:
    endpoint: str
    main_models: tuple[str, ...]
    vision_models: tuple[str, ...]


PROVIDERS = {
    "qwen": ProviderProfile("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                            ("qwen3.8-flash", "qwen3.8-max"), ("qwen3.8-max", "qwen3.8-flash")),
    "qwen-intl": ProviderProfile("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
                                 ("qwen3.8-flash", "qwen3.8-max"), ("qwen3.8-max", "qwen3.8-flash")),
    "custom": ProviderProfile("", (), ()),
    "xiaomi-mimo": ProviderProfile("https://api.xiaomimimo.com/v1/chat/completions",
                                   ("mimo-v2.5-pro", "mimo-v2.5"), ("mimo-v2.5",)),
    "deepseek": ProviderProfile("https://api.deepseek.com/chat/completions",
                               ("deepseek-v4-pro", "deepseek-v4-flash"), ("deepseek-v4-flash-vision-exp",)),
}


def resolve_models(provider, model=None, vision_model=None):
    if provider not in PROVIDERS:
        raise ValueError("Unsupported model provider")
    profile = PROVIDERS[provider]
    if provider == "custom":
        main, vision = model, vision_model or model
        if not all(isinstance(value, str) and re.fullmatch(r"[!-~]{1,200}", value) for value in (main, vision)):
            raise ValueError("Custom provider requires explicit model names")
        return main, vision
    main = model if model is not None else profile.main_models[0]
    vision = vision_model if vision_model is not None else profile.vision_models[0]
    if main not in profile.main_models or vision not in profile.vision_models:
        raise ValueError("Model does not match the selected provider or capability")
    return main, vision


def normalize_endpoint(endpoint):
    try:
        parsed = urlsplit(endpoint.strip().rstrip("/"))
        local = parsed.hostname in {"localhost", "127.0.0.1", "::1"}
        if (not parsed.hostname or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment
                or not (parsed.scheme == "https" or parsed.scheme == "http" and local)
                or parsed.port == 0 or "\\" in endpoint or any(ord(char) < 33 for char in endpoint.strip())):
            raise ValueError()
        path = parsed.path.rstrip("/")
        if ".." in path or re.search(r"%2f|%5c|%2e", path, re.I):
            raise ValueError()
        if not path.endswith("/chat/completions"):
            path += "/chat/completions"
        return urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))
    except (ValueError, AttributeError):
        raise ValueError("Model endpoint must be HTTPS without credentials, query, or fragment") from None


def resolve_configuration(config):
    """Only trusted host configuration chooses destinations; request bodies cannot supply them."""
    enabled = config.vision_enhancement_enabled
    if enabled is None:
        enabled = config.provider != "custom"
    if not isinstance(enabled, bool):
        raise ValueError("vision_enhancement_enabled must be boolean")
    vision_provider = (config.vision_provider or config.provider) if enabled else config.provider
    main, default_vision = resolve_models(config.provider, config.model)
    if enabled:
        if vision_provider == config.provider:
            _, vision = resolve_models(config.provider, main, config.vision_model)
        else:
            _, vision = resolve_models(vision_provider, config.vision_model if vision_provider == "custom" else None, config.vision_model)
    else:
        _, vision = resolve_models(config.provider, main, main)
    endpoint = normalize_endpoint(config.provider_endpoint or PROVIDERS[config.provider].endpoint)
    vision_endpoint = normalize_endpoint((config.vision_endpoint or (endpoint if vision_provider == config.provider else PROVIDERS[vision_provider].endpoint)) if enabled else endpoint)
    # Presets remain bound to their audited destination. Alternate hosts use explicit custom configuration.
    for provider, destination in ((config.provider, endpoint), (vision_provider, vision_endpoint)):
        if provider != "custom" and destination != PROVIDERS[provider].endpoint:
            raise ValueError("Preset provider endpoint cannot be replaced; use custom")
    separate = vision_provider != config.provider or vision_endpoint != endpoint
    if enabled and separate and not (config.vision_api_key_file or config.vision_headers_file):
        raise ValueError("A separate vision provider requires separate credentials")
    return dict(model=main, vision_model=vision, vision_provider=vision_provider,
                provider_endpoint=endpoint, vision_endpoint=vision_endpoint, vision_enhancement_enabled=enabled)


def connection_for(config, auxiliary=False):
    if not auxiliary or not config.vision_enhancement_enabled:
        return config.provider, config.model, config.provider_endpoint, config.api_key_file, config.provider_headers_file
    separate = config.vision_provider != config.provider or config.vision_endpoint != config.provider_endpoint
    return (config.vision_provider, config.vision_model, config.vision_endpoint,
            config.vision_api_key_file if separate or config.vision_api_key_file else config.api_key_file,
            config.vision_headers_file if separate or config.vision_headers_file else config.provider_headers_file)


def has_media(messages):
    return any(isinstance(part, dict) and part.get("type") not in {"text"}
               for message in messages if isinstance(message, dict) and isinstance(message.get("content"), list)
               for part in message["content"])


def adapt_payload(payload, provider, *, auxiliary=False):
    # Both providers enable thinking by default; action loops use a bounded,
    # explicitly non-thinking request until thinking replay is separately tested.
    for field in list(payload):
        if field.startswith("_doppel_"):
            payload.pop(field)
    payload.pop("thinking", None)
    payload.pop("enable_thinking", None)
    payload.pop("reasoning_effort", None)
    payload.pop("parallel_tool_calls", None)
    if provider in {"qwen", "qwen-intl"}:
        payload["enable_thinking"] = not auxiliary
        if not auxiliary:
            payload["reasoning_effort"] = "low"
    elif provider in {"xiaomi-mimo", "deepseek"}:
        payload["thinking"] = {"type": "disabled"}
    if provider == "xiaomi-mimo":
        payload["max_completion_tokens"] = payload.pop("max_tokens")
        payload.pop("tool_choice", None)
        if payload.get("tools"):
            payload["tool_choice"] = "auto"
    return payload


def usage_details(usage):
    """Breakdowns are subsets of billed totals, never extra token charges."""
    details = {}
    for group, names in (("prompt_tokens_details", ("cached_tokens", "image_tokens", "video_tokens", "audio_tokens")),
                         ("completion_tokens_details", ("reasoning_tokens",))):
        source = usage.get(group)
        if not isinstance(source, dict):
            continue
        for name in names:
            value = source.get(name)
            if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                details[name] = value
    return details
