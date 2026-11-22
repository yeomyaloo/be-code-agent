import DOMPurify from 'dompurify'

type Review = { author: string; body: string; summaryHtml: string }

export function ReviewBody({ review }: { review: Review }) {
  return <div className="review" dangerouslySetInnerHTML={{ __html: review.body }} />
}

export function ReviewSummary({ review }: { review: Review }) {
  return <p dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(review.summaryHtml) }} />
}

export function goBack(params: URLSearchParams) {
  const target = params.get('returnTo') ?? '/'
  window.location.href = target
}
