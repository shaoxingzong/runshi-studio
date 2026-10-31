<script setup lang="ts">
import { computed, ref, watch } from 'vue';

/** 用户头像的统一默认图：缺失头像或原头像加载失败时使用 */
const DEFAULT_AVATAR = '/default-avatar.png';

/** 带兜底的图片：封面退回「首字 + 渐变色块」，头像退回默认头像，而不是留一个裂图 */
const props = withDefaults(
    defineProps<{
        /** 图片地址；为空字符串 / null 时直接走兜底，不发起请求 */
        src?: string | null;
        /** 兜底显示的文字（取第一个字）；留空则只显示色块 */
        alt?: string;
        /** 兜底色块的种子：同一个名字每次渲染颜色一致，避免刷新后变色 */
        seed?: string;
        /** 图片填充方式：封面用 cover，头像用 cover 裁成圆形 */
        fit?: 'cover' | 'contain';
        /**
         * 兜底方式：initial = 首字渐变块（封面/项目图）；avatar = 默认头像（所有用户头像）
         */
        fallback?: 'initial' | 'avatar';
    }>(),
    { src: '', alt: '', seed: '', fit: 'cover', fallback: 'initial' },
);

/** 记录「这张图加载失败了」，用于切到兜底。换图时重置 */
const failed = ref(false);
/** 默认头像本身也加载失败时，最后退回首字色块（正常部署下不会发生） */
const fallbackFailed = ref(false);
watch(
    () => [props.src, props.fallback],
    () => {
        failed.value = false;
        fallbackFailed.value = false;
    },
);

const showImage = computed(() => !!props.src && !failed.value);
/** 头像模式：没有可用头像时展示统一默认头像 */
const showDefaultAvatar = computed(
    () => !showImage.value && props.fallback === 'avatar' && !fallbackFailed.value,
);

/** 兜底文字：取第一个非空白字符（中文取字，英文取首字母） */
const fallbackText = computed(() => (props.alt || props.seed || '').trim().slice(0, 1).toUpperCase());

/**
 * 根据种子算一个稳定的渐变色。
 *
 * 色相刻意限制在品牌蓝区间（204°–227°）：早期用全色环时，帖子/项目封面
 * 会出现亮绿、柠檬黄这类与蓝红 Logo 完全冲突的色块，看起来像「图片坏了」。
 * 范围内微调仍能让不同条目的封面有区分度，但不会滑到紫色。
 */
const BRAND_HUE_START = 204;
const BRAND_HUE_RANGE = 24;

const gradient = computed(() => {
    const key = props.seed || props.alt || '';
    let hash = 0;
    for (let i = 0; i < key.length; i += 1) {
        hash = (hash * 31 + key.charCodeAt(i)) % 360;
    }
    const hue = BRAND_HUE_START + (hash % BRAND_HUE_RANGE);
    return `linear-gradient(135deg, hsl(${hue} 72% 58%), hsl(${(hue + 12) % 360} 78% 44%))`;
});

function onError() {
    failed.value = true;
}

function onDefaultAvatarError() {
    fallbackFailed.value = true;
}
</script>

<template>
    <img
        v-if="showImage"
        :src="src ?? ''"
        :alt="alt"
        :style="{ objectFit: fit }"
        class="img-fallback__img"
        @error="onError"
    />
    <img
        v-else-if="showDefaultAvatar"
        :src="DEFAULT_AVATAR"
        :alt="alt"
        :style="{ objectFit: fit }"
        class="img-fallback__img"
        @error="onDefaultAvatarError"
    />
    <span v-else class="img-fallback__box" :style="{ background: gradient }" aria-hidden="true">
        {{ fallbackText }}
    </span>
</template>

<style scoped>
.img-fallback__img {
    display: block;
    width: 100%;
    height: 100%;
}

/* 兜底色块铺满容器：父容器决定尺寸（圆形头像 / 矩形封面都适用）*/
.img-fallback__box {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    color: #fff;
    font-weight: 600;
    font-size: 1.2em;
    letter-spacing: 0.02em;
    user-select: none;
}
</style>
