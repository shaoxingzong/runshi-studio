<script setup lang="ts">
import { computed, ref, watch } from 'vue';

/**
 * 带兜底的图片：加载失败退回「首字 + 渐变色块」，而不是留一个裂图
 *
 * 图片地址可能来自外部图床（会挂）或成员上传后被清理，原生 <img> 只剩破图图标，
 * 比不展示更难看。用首字而不是统一默认图：一页多个项目用同一张图会分不清谁是谁。
 */
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
    }>(),
    { src: '', alt: '', seed: '', fit: 'cover' },
);

/** 记录「这张图加载失败了」，用于切到兜底。换图时重置 */
const failed = ref(false);
watch(
    () => props.src,
    () => {
        failed.value = false;
    },
);

const showImage = computed(() => !!props.src && !failed.value);

/** 兜底文字：取第一个非空白字符（中文取字，英文取首字母） */
const fallbackText = computed(() => (props.alt || props.seed || '').trim().slice(0, 1).toUpperCase());

/**
 * 根据种子算一个稳定的渐变色
 *
 * 不用随机色：同一张卡片每次渲染都变色会很乱，这里保证「同样的名字 → 同样的颜色」。
 */
const gradient = computed(() => {
    const key = props.seed || props.alt || '';
    let hash = 0;
    for (let i = 0; i < key.length; i += 1) {
        hash = (hash * 31 + key.charCodeAt(i)) % 360;
    }
    const hue = hash;
    return `linear-gradient(135deg, hsl(${hue} 62% 58%), hsl(${(hue + 32) % 360} 66% 46%))`;
});

function onError() {
    failed.value = true;
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

/* 兜底色块铺满容器：父容器决定尺寸（圆形头像 / 矩形封面都适用） */
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
