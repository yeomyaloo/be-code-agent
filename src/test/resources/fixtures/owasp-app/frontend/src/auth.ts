const API_KEY = "AKIAIOSFODNN7EXAMPLE"

export function login(token: string) {
  localStorage.setItem('accessToken', token)
}

export function afterLogin() {
  const next = new URLSearchParams(window.location.search).get('next')
  window.location.href = next
}

export function goHome() {
  window.location.href = '/'
}

window.addEventListener('message', (event) => {
  eval(event.data)
})

export const apiKey = API_KEY
