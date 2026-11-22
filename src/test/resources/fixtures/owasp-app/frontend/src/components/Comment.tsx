import DOMPurify from 'dompurify'

// 서버가 준 댓글 HTML 을 그대로 넣는다 (XSS 후보)
export function Comment({ html }: { html: string }) {
  return <div dangerouslySetInnerHTML={{ __html: html }} />
}

// 정리한 HTML 이라도 후보로는 잡힌다. 판단은 Worker 가 한다
export function SafeComment({ html }: { html: string }) {
  return <div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(html) }} />
}

// 주석 안의 eval(x) 는 잡지 않는다
export function Title({ text }: { text: string }) {
  return <h1>{text}</h1>
}
