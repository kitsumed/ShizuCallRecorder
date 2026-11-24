<script setup lang="ts">
import useEmblaCarousel from 'embla-carousel-vue'
import AutoScroll from 'embla-carousel-auto-scroll'

export interface Review {
  author: string
  platform: string
  url: string
  quote: string
}

defineProps<{
  reviews: Review[]
}>()

const autoScroll = AutoScroll({
  speed: 1,
  startDelay: 0,
  stopOnInteraction: false,
  stopOnMouseEnter: true,
  stopOnFocusIn: true,
})

const [emblaRef] = useEmblaCarousel(
  {
    loop: true,
    align: 'start',
    dragFree: true,
  },
  [autoScroll]
)
</script>

<template>
  <section
    class="embla"
    aria-roledescription="carousel"
    aria-label="User reviews"
  >
    <div ref="emblaRef" class="embla__viewport">
      <div class="embla__container">
        <div
          v-for="(review, index) in reviews"
          :key="`${review.author}-${index}`"
          class="embla__slide"
          role="group"
          aria-roledescription="slide"
          :aria-label="`Review ${index + 1} of ${reviews.length}`"
        >
          <figure class="review-card">
            <blockquote :cite="review.url" class="review-quote">
              <p>“{{ review.quote }}”</p>
            </blockquote>
            <figcaption class="review-meta">
              <span class="review-author">{{ review.author }}</span>
              <a
                :href="review.url"
                target="_blank"
                rel="nofollow ugc noopener"
                class="review-platform"
              >
                {{ review.platform }}
              </a>
            </figcaption>
          </figure>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.embla {
  margin: 2rem 0;
  --slide-spacing: 1rem;
  --slide-size: 85%;
}

@media (min-width: 768px) {
  .embla {
    --slide-size: 45%;
  }
}

.embla__viewport {
  overflow: hidden;
  mask-image: linear-gradient(
    to right,
    transparent,
    black 4%,
    black 96%,
    transparent
  );
}

.embla__container {
  display: flex;
  touch-action: pan-y pinch-zoom;
  margin-left: calc(var(--slide-spacing) * -1);
}

.embla__slide {
  flex: 0 0 var(--slide-size);
  min-width: 0;
  padding-left: var(--slide-spacing);
}

.review-card {
  margin: 0; /* Reset default browser <figure> margin */
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 1.5rem;
  border-radius: 12px;
  background-color: var(--vp-c-bg-soft);
  border: 1px solid var(--vp-c-divider);
}

.review-quote {
  margin: 0 0 1.25rem;
  padding: 0;
  border: none; /* Override VitePress default blockquote left-border */
  font-size: 0.95rem;
  line-height: 1.6;
  color: var(--vp-c-text-1);
}

.review-quote p {
  margin: 0;
}

.review-meta {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  font-size: 0.85rem;
}

.review-author {
  font-weight: 600;
  color: var(--vp-c-text-1);
}

.review-platform {
  width: fit-content;
  color: var(--vp-c-text-2);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.review-platform:hover {
  color: var(--vp-c-brand-1);
}
</style>