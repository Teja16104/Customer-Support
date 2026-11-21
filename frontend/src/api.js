import { useCallback, useEffect, useState } from 'react'

const base = '/api/v1'
let csrf
let pending

export function forgetCsrf() {
  csrf = undefined
  pending = undefined
}

async function readToken() {
  if (csrf) return csrf
  if (!pending) {
    pending = fetch(base + '/auth/csrf', { cache: 'no-store' })
      .then(async response => {
        if (!response.ok) throw new Error('Could not start a session. Check that Spring Boot is running.')
        csrf = (await response.json()).token
        return csrf
      })
      .finally(() => { pending = undefined })
  }
  return pending
}

export async function request(path, { method = 'GET', body, version, key, signal } = {}) {
  const headers = { Accept: 'application/json' }
  if (method !== 'GET') headers['X-CSRF-TOKEN'] = await readToken()
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (version !== undefined) headers['If-Match'] = '"' + version + '"'
  if (key) headers['Idempotency-Key'] = key

  let response
  try {
    response = await fetch(base + path, {
      method, headers, signal, cache: 'no-store',
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new Error('Could not reach the backend. Check that Spring Boot is running on port 8080.')
  }

  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    if (response.status === 403) forgetCsrf()
    if (response.status === 401 && !path.startsWith('/auth/')) {
      window.dispatchEvent(new Event('relay:session-expired'))
    }
    const message = {
      400: 'Check the fields and try again.',
      401: 'Your email or password could not be verified.',
      403: 'Access was denied. Check your role or try again to refresh your session token.',
      409: 'The request conflicts with the current ticket or account.',
      412: 'This ticket changed. Review the updated details and try again.',
      500: 'The backend could not complete the request.',
      502: 'Spring Boot is unavailable on port 8080.',
      503: 'The backend is temporarily unavailable.',
    }[response.status]
    throw Object.assign(
      new Error(problem.detail || problem.message || message || ('Request failed (' + response.status + ').')),
      { status: response.status },
    )
  }

  if (path === '/auth/login' || path === '/auth/logout') forgetCsrf()
  return response.status === 204 ? null : response.json()
}

export function query(fields) {
  return new URLSearchParams(
    Object.entries(fields).filter(([, value]) => value !== '' && value != null),
  ).toString()
}

export function useData(path, poll = 0) {
  const [state, setState] = useState({ path: null, data: null, error: null })
  const [revision, setRevision] = useState(0)
  const refresh = useCallback(() => setRevision(value => value + 1), [])

  useEffect(() => {
    if (!path) return
    let active = true
    let call

    async function load() {
      if (call) return
      const controller = new AbortController()
      call = controller
      try {
        const data = await request(path, { signal: controller.signal })
        if (active) setState({ path, data, error: null })
      } catch (error) {
        if (active && error.name !== 'AbortError') {
          setState(previous => ({ path, data: previous.path === path ? previous.data : null, error }))
        }
      } finally {
        call = undefined
      }
    }

    load()
    const timer = poll ? window.setInterval(() => { if (!document.hidden) load() }, poll) : null
    return () => {
      active = false
      call?.abort()
      if (timer) window.clearInterval(timer)
    }
  }, [path, poll, revision])

  const current = state.path === path ? state : { path, data: null, error: null }
  return { ...current, refresh, loading: Boolean(path && !current.data && !current.error) }
}