import { useCallback, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import {
  BrowserRouter, Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate, useParams,
} from 'react-router-dom'
import {
  Activity, ArrowDownToLine, ArrowLeft, ArrowRight, Bell, Check, ChevronLeft, ChevronRight,
  CircleAlert, Clock3, Headphones, Inbox, LayoutDashboard, LogOut, Menu, MessageSquare, Plus,
  Radio, Send, Settings2, Sparkles, Star, Ticket, X,
} from 'lucide-react'
import { forgetCsrf, query, request, useData } from './api'
import './style.css'

const statuses = ['OPEN', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED']
const priorities = ['URGENT', 'HIGH', 'MEDIUM', 'LOW']
const active = new Set(statuses.slice(0, 3))
const label = value => value
  ? String(value).toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase())
  : '—'
const formatTime = value => value ? new Date(value).toLocaleString([], {
  month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
}) : '—'
const urgency = { URGENT: 'urgent', HIGH: 'high', MEDIUM: 'blue', LOW: 'green' }
const colors = {
  OPEN: 'violet', IN_PROGRESS: 'blue', WAITING_CUSTOMER: 'high',
  RESOLVED: 'green', CLOSED: 'gray', MODEL: 'violet', FALLBACK: 'high', COMPLETE: 'green',
}

function Badge({ value }) {
  return <span className={'badge ' + (urgency[value] || colors[value] || 'gray')}><i />{label(value)}</span>
}

function Brand() {
  return <span className="brand">
    <span className="brand-icon"><Radio size={21} /></span>
    <span>relay<span className="brand-dot">.</span></span>
  </span>
}

function Panel({ title, icon: Icon, children, className = '' }) {
  return <section className={'panel ' + className}>
    {title && <h2 className="panel-title">{Icon && <Icon size={18} />}{title}</h2>}
    {children}
  </section>
}

function Problem({ error }) {
  return error ? <div className="problem" role="alert"><CircleAlert size={18} /><span>{error.message}</span></div> : null
}

function Loading({ text = 'Loading…' }) {
  return <div className="loading" role="status"><span className="spinner" />{text}</div>
}

function Empty({ text }) {
  return <div className="empty"><Inbox size={25} /><p>{text}</p></div>
}

function PageTitle({ eyebrow, title, subtitle, children }) {
  return <header className="page-head">
    <div>
      {eyebrow && <div className="eyebrow">{eyebrow}</div>}
      <h1>{title}</h1>{subtitle && <p>{subtitle}</p>}
    </div>
    {children && <div className="actions">{children}</div>}
  </header>
}

function useAction(notify) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const locked = useRef(false)

  async function run(task, success, onError) {
    if (locked.current) return false
    locked.current = true
    setBusy(true)
    setError(null)
    try {
      await task()
      if (success) notify?.(success)
      return true
    } catch (failure) {
      setError(failure)
      onError?.(failure)
      return false
    } finally {
      locked.current = false
      setBusy(false)
    }
  }

  return { run, busy, error }
}

function Auth({ register, signedIn, sessionError }) {
  const action = useAction()
  const navigate = useNavigate()

  function submit(event) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const credentials = { email: form.get('email').trim(), password: form.get('password') }
    action.run(async () => {
      if (register) await request('/auth/register', {
        method: 'POST', body: { ...credentials, displayName: form.get('displayName').trim() },
      })
      const user = await request('/auth/login', { method: 'POST', body: credentials })
      signedIn(user)
      navigate(user.role === 'ADMIN' ? '/overview' : '/tickets', { replace: true })
    })
  }

  return <main className="auth-layout">
    <aside className="auth-art">
      <Brand />
      <div className="auth-story">
        <div className="orbit" aria-hidden="true">
          <span className="orbit-ring ring-one" /><span className="orbit-ring ring-two" /><span className="orbit-ring ring-three" />
          <span className="orbit-core"><Headphones size={51} strokeWidth={1.45} /></span>
          <span className="orbit-node node-one"><MessageSquare size={19} /></span>
          <span className="orbit-node node-two"><Check size={20} /></span>
          <span className="orbit-node node-three"><Sparkles size={18} /></span>
        </div>
        <h1>Support<br /><em>ticket portal</em></h1>
        <p>Submit complaints, track tickets, and read replies from your support team.</p>
      </div>
    </aside>
    <div className="auth-main">
      <div className="auth-card enter">
        <div className="mobile-brand"><Brand /></div>
        <div className="eyebrow">RELAY SUPPORT</div>
        <h2>{register ? 'Create an account' : 'Welcome back'}</h2>
        <p>{register ? 'Create a customer account to submit and track requests.' : 'Sign in to your support workspace.'}</p>
        <Problem error={action.error || sessionError} />
        <form className="form" onSubmit={submit}>
          {register && <label>Full name<input name="displayName" autoComplete="name" maxLength={100} required /></label>}
          <label>Email address<input name="email" type="email" autoComplete="username"
            maxLength={254} placeholder="you@example.com" required /></label>
          <label>Password<input name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'}
            minLength={register ? 12 : 1} maxLength={64}
            placeholder={register ? 'At least 12 characters' : 'Enter your password'} required /></label>
          <button className="button primary full" disabled={action.busy}>
            {action.busy ? 'Please wait…' : register ? 'Create account' : 'Sign in'}<ArrowRight size={17} />
          </button>
        </form>
        <div className="auth-switch">{register ? 'Already registered?' : 'New to Relay?'}{' '}
          <Link to={register ? '/login' : '/register'}>{register ? 'Sign in' : 'Create an account'}</Link>
        </div>
      </div>
    </div>
  </main>
}

function App() {
  const [user, setUser] = useState(undefined)
  const [sessionError, setSessionError] = useState(null)
  const [toast, setToast] = useState(null)
  const notify = useCallback(message => setToast({ message }), [])

  useEffect(() => {
    const controller = new AbortController()
    request('/auth/me', { signal: controller.signal }).then(setUser).catch(error => {
      if (error.name === 'AbortError') return
      if (error.status !== 401) setSessionError(error)
      setUser(null)
    })
    function expire() {
      forgetCsrf()
      setUser(null)
      setToast(null)
      setSessionError(new Error('Your session ended. Sign in again.'))
    }
    window.addEventListener('relay:session-expired', expire)
    return () => {
      controller.abort()
      window.removeEventListener('relay:session-expired', expire)
    }
  }, [])

  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(null), 3800)
    return () => clearTimeout(timer)
  }, [toast])

  function signedIn(profile) {
    setSessionError(null)
    setUser(profile)
  }

  if (user === undefined) return <Loading text="Opening Relay…" />
  if (!user) return <Routes>
    <Route path="/login" element={<Auth key="login" sessionError={sessionError} signedIn={signedIn} />} />
    <Route path="/register" element={<Auth key="register" register signedIn={signedIn} />} />
    <Route path="*" element={<Navigate to="/login" replace />} />
  </Routes>

  return <>
    <Workspace user={user} notify={notify} signedOut={() => { setUser(null); setToast(null) }} />
    {toast && <div className="toast" role="status"><span><Check size={16} /></span>{toast.message}
      <button className="icon-button" aria-label="Dismiss message" onClick={() => setToast(null)}><X size={16} /></button>
    </div>}
  </>
}

function Workspace({ user, notify, signedOut }) {
  const admin = user.role === 'ADMIN'
  const staff = user.role !== 'CUSTOMER'
  const home = admin ? '/overview' : '/tickets'
  const location = useLocation()
  const [menu, setMenu] = useState(false)
  const action = useAction()
  const entries = [
    ...(admin ? [[LayoutDashboard, '/overview', 'Overview']] : []),
    [Inbox, '/tickets', staff ? 'Ticket queue' : 'My tickets'],
    ...(staff ? [[Bell, '/alerts', 'SLA alerts']] : [[Plus, '/new', 'New complaint']]),
    ...(admin ? [[Settings2, '/team', 'Team & routing']] : []),
  ]

  useEffect(() => { setMenu(false) }, [location.pathname])
  useEffect(() => {
    if (!menu) return
    function close(event) { if (event.key === 'Escape') setMenu(false) }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [menu])

  return <div className="workspace">
    {menu && <button className="scrim" aria-label="Close navigation" onClick={() => setMenu(false)} />}
    <aside className={'sidebar' + (menu ? ' shown' : '')} id="main-navigation">
      <Link to={home} aria-label="Relay home"><Brand /></Link>
      <div className="nav-title">Workspace</div>
      <nav aria-label="Main navigation">
        {entries.map(([Icon, path, text]) => <NavLink key={path} to={path}><Icon size={18} />{text}</NavLink>)}
      </nav>
      <div className="profile">
        <span className="avatar">{user.displayName[0]?.toUpperCase()}</span>
        <div><strong>{user.displayName}</strong><small>{label(user.role)}</small></div>
        <button className="icon-button" title="Sign out" aria-label="Sign out" disabled={action.busy}
          onClick={() => action.run(async () => {
            await request('/auth/logout', { method: 'POST' })
            signedOut()
          })}>
          <LogOut size={17} />
        </button>
      </div>
    </aside>
    <div className="workspace-body">
      <header className="topbar">
        <div className="topbar-left">
          <button className="icon-button mobile-menu" type="button" aria-label="Toggle navigation"
            aria-controls="main-navigation" aria-expanded={menu} onClick={() => setMenu(!menu)}><Menu size={21} /></button>
          <span>Support workspace</span>
        </div>
        {staff ? <Link to="/alerts" className="icon-button" aria-label="View SLA alerts"><Bell size={19} /></Link>
          : <span className="top-role"><Headphones size={16} />Customer portal</span>}
      </header>
      <main className="content">
        <Problem error={action.error} />
        <div className="enter" key={location.pathname}>
          <Routes>
            {admin && <Route path="/overview" element={<Overview user={user} />} />}
            <Route path="/tickets" element={<Queue user={user} />} />
            <Route path="/tickets/:key" element={<TicketRoute user={user} notify={notify} />} />
            {!staff && <Route path="/new" element={<NewComplaint notify={notify} />} />}
            {staff && <Route path="/alerts" element={<Alerts notify={notify} />} />}
            {admin && <Route path="/team" element={<Team notify={notify} />} />}
            <Route path="*" element={<Navigate to={home} replace />} />
          </Routes>
        </div>
      </main>
    </div>
  </div>
}

function Queue({ user }) {
  const staff = user.role !== 'CUSTOMER'
  const [filters, setFilters] = useState({ status: '', priority: '', categoryCode: '', scope: '', page: 0 })
  const catalog = useData('/categories')
  const { data, error, loading } = useData('/tickets?' + query({ ...filters, size: 12 }), 10000)

  function filter(event) {
    setFilters(previous => ({ ...previous, [event.target.name]: event.target.value, page: 0 }))
  }

  return <>
    <PageTitle eyebrow={staff ? 'SUPPORT OPERATIONS' : 'CUSTOMER PORTAL'} title={staff ? 'Ticket queue' : 'My tickets'}
      subtitle={staff ? 'Prioritize requests and see who’s working on them.' : 'See where each request stands and follow replies from your support team.'}>
      {!staff && <Link className="button primary" to="/new"><Plus size={18} />New complaint</Link>}
    </PageTitle>
    <Panel>
      <div className="filter-row">
        <label>Status<select name="status" value={filters.status} onChange={filter}>
          <option value="">All statuses</option>
          {statuses.map(value => <option key={value} value={value}>{label(value)}</option>)}
        </select></label>
        <label>Priority<select name="priority" value={filters.priority} onChange={filter}>
          <option value="">All priorities</option>
          {priorities.map(value => <option key={value} value={value}>{label(value)}</option>)}
        </select></label>
        <label>Category<select name="categoryCode" value={filters.categoryCode} onChange={filter}>
          <option value="">All categories</option>
          {catalog.data?.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}
        </select></label>
        {user.role === 'ADMIN' && <label>Assignment<select name="scope" value={filters.scope} onChange={filter}>
          <option value="">All assignments</option><option value="UNASSIGNED">Unassigned</option>
        </select></label>}
      </div>
      <Problem error={error} />
      <Problem error={catalog.error} />
      {loading && <Loading text="Loading tickets…" />}
      {data && data.items.length === 0 && <Empty text="No tickets match these filters." />}
      {!!data?.items.length && <table className="ticket-table">
        <thead><tr><th>Ticket</th><th>Status</th><th>Priority</th>{staff && <th>Assigned to</th>}<th>Updated</th></tr></thead>
        <tbody>{data.items.map(ticket => <tr key={ticket.key}>
          <td data-label="Ticket"><Link to={'/tickets/' + ticket.key}>{ticket.subject}</Link><small>{ticket.key}</small></td>
          <td data-label="Status"><Badge value={ticket.status} /></td>
          <td data-label="Priority"><Badge value={ticket.priority} /></td>
          {staff && <td data-label="Assigned to">{ticket.staff?.assignee?.displayName || 'Unassigned'}</td>}
          <td data-label="Updated">{formatTime(ticket.updatedAt)}</td>
        </tr>)}</tbody>
      </table>}
      {data && data.total > data.size && <div className="pager">
        <button type="button" className="icon-button" aria-label="Previous page" disabled={filters.page === 0}
          onClick={() => setFilters({ ...filters, page: filters.page - 1 })}><ChevronLeft size={19} /></button>
        <span>Page {filters.page + 1} of {Math.ceil(data.total / data.size)}</span>
        <button type="button" className="icon-button" aria-label="Next page" disabled={(filters.page + 1) * data.size >= data.total}
          onClick={() => setFilters({ ...filters, page: filters.page + 1 })}><ChevronRight size={19} /></button>
      </div>}
    </Panel>
  </>
}

function NewComplaint({ notify }) {
  const action = useAction(notify)
  const submission = useRef(null)
  const navigate = useNavigate()

  function submit(event) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const body = { subject: form.get('subject').trim(), description: form.get('description').trim() }
    const signature = JSON.stringify(body)
    if (submission.current?.signature !== signature) {
      submission.current = { signature, key: crypto.randomUUID() }
    }
    action.run(async () => {
      const ticket = await request('/tickets', { method: 'POST', body, key: submission.current.key })
      navigate('/tickets/' + ticket.key)
    }, 'Complaint submitted')
  }

  return <>
    <Link className="back" to="/tickets"><ArrowLeft size={16} />My tickets</Link>
    <PageTitle title="New complaint" subtitle="Tell us what happened and what you need help with." />
    <Panel title="Complaint details" icon={Ticket} className="form-panel">
      <Problem error={action.error} />
      <form className="form" onSubmit={submit}>
        <label>Subject<input name="subject" minLength={3} maxLength={160}
          placeholder="Example: Charged twice for my subscription" required /></label>
        <label>Description<textarea name="description" minLength={10} maxLength={8000} rows={7}
          placeholder="Describe the issue, what you expected, and when it happened." required /></label>
        <div className="actions">
          <Link to="/tickets" className="button">Cancel</Link>
          <button className="button primary" disabled={action.busy}>
            <Send size={16} />{action.busy ? 'Submitting…' : 'Submit complaint'}
          </button>
        </div>
      </form>
    </Panel>
  </>
}

function TicketRoute(props) {
  const { key } = useParams()
  return <TicketDetail key={key} ticketKey={key} {...props} />
}

const staffMoves = {
  OPEN: ['IN_PROGRESS'], IN_PROGRESS: ['WAITING_CUSTOMER', 'RESOLVED'],
  WAITING_CUSTOMER: ['IN_PROGRESS', 'RESOLVED'], RESOLVED: ['IN_PROGRESS', 'CLOSED'], CLOSED: [],
}
const customerMoves = { WAITING_CUSTOMER: ['IN_PROGRESS'], RESOLVED: ['CLOSED'] }
const moveLabel = {
  IN_PROGRESS: 'Start work', WAITING_CUSTOMER: 'Wait for customer',
  RESOLVED: 'Resolve ticket', CLOSED: 'Close ticket',
}

function TicketDetail({ ticketKey, user, notify }) {
  const path = '/tickets/' + encodeURIComponent(ticketKey)
  const staff = user.role !== 'CUSTOMER'
  const admin = user.role === 'ADMIN'
  const ticket = useData(path, 7000)
  const comments = useData(path + '/comments', 10000)
  const history = useData(path + '/events', 10000)
  const similar = useData(staff ? path + '/similar' : null)
  const feedback = useData(ticket.data?.feedbackSubmitted ? path + '/feedback' : null)
  const [tab, setTab] = useState('conversation')
  const [reply, setReply] = useState('')
  const [visibility, setVisibility] = useState('PUBLIC')
  const [summary, setSummary] = useState('')
  const action = useAction(notify)
  const composer = useRef(null)
  const data = ticket.data

  function update(suffix, method, body, success, afterSave) {
    action.run(async () => {
      await request(path + suffix, { method, body, version: data.version })
      ticket.refresh()
      comments.refresh()
      history.refresh()
      similar.refresh()
      afterSave?.()
    }, success, error => { if (error.status === 412) ticket.refresh() })
  }

  if (!data) return <>
    <Link className="back" to="/tickets"><ArrowLeft size={16} />Tickets</Link>
    <Problem error={ticket.error} />
    {ticket.loading && <Loading text="Opening ticket…" />}
  </>

  const editable = active.has(data.status)
  const moves = staff ? staffMoves[data.status] || [] : customerMoves[data.status] || []

  return <>
    <Link className="back" to="/tickets"><ArrowLeft size={16} />{staff ? 'Ticket queue' : 'My tickets'}</Link>
    <PageTitle eyebrow={data.key} title={data.subject} subtitle={data.category.name + ' · Opened ' + formatTime(data.createdAt)}>
      <Badge value={data.status} /><Badge value={data.priority} />
    </PageTitle>
    <Problem error={action.error || ticket.error} />
    <div className="detail-grid">
      <div className="stack">
        <Panel title="Complaint" icon={Ticket}><p className="body-copy">{data.description}</p></Panel>
        <Panel>
          <div className="tabs" role="group" aria-label="Ticket activity">
            <button type="button" aria-pressed={tab === 'conversation'} onClick={() => setTab('conversation')}>
              <MessageSquare size={16} />Conversation
            </button>
            <button type="button" aria-pressed={tab === 'history'} onClick={() => setTab('history')}>
              <Clock3 size={16} />History
            </button>
          </div>
          {tab === 'conversation' ? <div className="enter">
            <Problem error={comments.error} />
            {comments.loading && <Loading text="Loading replies…" />}
            {comments.data?.length === 0 && <Empty text="No replies yet." />}
            <div className="message-list">{comments.data?.map(message => <article key={message.id}
              className={'message ' + (message.visibility === 'INTERNAL' ? 'internal' : '')}>
              <header><strong>{message.authorName}</strong><time>{formatTime(message.createdAt)}</time>
                {message.visibility === 'INTERNAL' && <Badge value="INTERNAL" />}</header>
              <p className="body-copy">{message.body}</p>
            </article>)}</div>
            {editable && <form className="form composer" onSubmit={event => {
              event.preventDefault()
              update('/comments', 'POST', { body: reply.trim(), visibility },
                visibility === 'INTERNAL' ? 'Internal note added' : 'Reply sent', () => setReply(''))
            }}>
              <label>{visibility === 'INTERNAL' ? 'Internal note' : 'Reply'}
                <textarea ref={composer} aria-label={visibility === 'INTERNAL' ? 'Internal note' : 'Reply'} value={reply}
                  onChange={event => setReply(event.target.value)} maxLength={5000} rows={4} required />
              </label>
              <div className="actions">
                {staff && <select aria-label="Reply visibility" value={visibility} onChange={event => setVisibility(event.target.value)}>
                  <option value="PUBLIC">Public reply</option><option value="INTERNAL">Internal note</option>
                </select>}
                <button className="button primary" disabled={action.busy || !reply.trim()}>
                  <Send size={16} />{visibility === 'INTERNAL' ? 'Save note' : 'Send reply'}
                </button>
              </div>
            </form>}
          </div> : <div className="enter">
            <Problem error={history.error} />
            {history.loading && <Loading text="Loading history…" />}
            <ol className="timeline">{history.data?.map((item, index) => <li key={item.createdAt + index}>
              <strong>{label(item.toStatus || item.type)}</strong><span>{formatTime(item.createdAt)}</span>
            </li>)}</ol>
          </div>}
        </Panel>
        {data.status === 'CLOSED' && (!staff || data.feedbackSubmitted) && <Panel title="Customer feedback" icon={Star}>
          <Problem error={feedback.error} />
          {feedback.loading && <Loading />}
          {feedback.data && <>
            <strong className="rating-value">{feedback.data.rating} / 5</strong>
            <p className="body-copy">{feedback.data.comment}</p>
          </>}
          {!data.feedbackSubmitted && !staff && <form className="form" onSubmit={event => {
            event.preventDefault()
            const form = new FormData(event.currentTarget)
            update('/feedback', 'POST', { rating: Number(form.get('rating')), comment: form.get('comment').trim() }, 'Feedback saved')
          }}>
            <label>Rating<select name="rating" defaultValue="5">
              {[5, 4, 3, 2, 1].map(n => <option key={n} value={n}>{n} / 5</option>)}
            </select></label>
            <label>Comment<textarea name="comment" rows={3} maxLength={1000} /></label>
            <button className="button primary" disabled={action.busy}>Save feedback</button>
          </form>}
        </Panel>}
      </div>
      <aside className="stack">
        <Panel title="Ticket status" icon={Activity}>
          <div className="meta"><Badge value={data.status} />{staff && <span>{data.staff.assignee?.displayName || 'Unassigned'}</span>}</div>
          {staff && <dl className="details">
            <div><dt>Customer</dt><dd>{data.staff.customer.displayName}</dd></div>
            <div><dt>Sentiment</dt><dd>{label(data.staff.sentiment)}</dd></div>
            <div><dt>AI triage</dt><dd><Badge value={data.staff.triageStatus} /></dd></div>
            {editable && <>
              <div><dt>First reply due</dt><dd>{formatTime(data.staff.sla.firstResponseDueAt)}</dd></div>
              <div><dt>Resolution due</dt><dd>{formatTime(data.staff.sla.resolutionDueAt)}</dd></div>
            </>}
          </dl>}
          {moves.includes('RESOLVED') && <label>Resolution summary<textarea rows={3} value={summary}
            maxLength={2000} onChange={event => setSummary(event.target.value)} /></label>}
          {!!moves.length && <div className="actions status-actions">{moves.map(status =>
            <button key={status} type="button" className={'button ' + (['RESOLVED', 'CLOSED'].includes(status) ? 'primary' : '')}
              disabled={action.busy || (status === 'RESOLVED' && !summary.trim())}
              onClick={() => update('/status', 'PATCH', {
                status, ...(status === 'RESOLVED' ? { resolutionSummary: summary.trim() } : {}),
              }, 'Status updated', () => { if (status === 'RESOLVED') setSummary('') })}>
              {status === 'IN_PROGRESS' && data.status === 'RESOLVED' ? 'Reopen ticket'
                : status === 'IN_PROGRESS' && !staff ? 'Resume ticket' : moveLabel[status]}
            </button>)}</div>}
          {staff && data.staff.resolutionSummary && <p className="body-copy">{data.staff.resolutionSummary}</p>}
        </Panel>
        {staff && <>
          <AiPanel path={path} ticket={data} notify={notify} editable={editable}
            useReply={text => {
              setTab('conversation')
              setReply(text)
              setVisibility('PUBLIC')
              requestAnimationFrame(() => composer.current?.focus())
            }} useSummary={setSummary} />
          {editable && <Triage key={data.category.code + data.priority} ticket={data} update={update} busy={action.busy} />}
          {admin && editable && <Assignment path={path} ticket={data} update={update} busy={action.busy} />}
          <Panel title="Similar tickets" icon={Inbox}>
            <Problem error={similar.error} />
            {similar.data?.length === 0 && <p className="subtle">No similar tickets in your queue.</p>}
            <ul className="compact-list">{similar.data?.map(item => <li key={item.key}>
              <Link to={'/tickets/' + item.key}>{item.subject}</Link><Badge value={item.status} />
            </li>)}</ul>
          </Panel>
          <AssignmentHistory path={path} version={data.version} />
        </>}
      </aside>
    </div>
  </>
}

function AiPanel({ path, ticket, notify, editable, useReply, useSummary }) {
  const [feature, setFeature] = useState('resolution')
  const [tone, setTone] = useState('EMPATHETIC')
  const [result, setResult] = useState(null)
  const action = useAction(notify)
  const stale = result && result.inputVersion !== ticket.version
  const draft = result?.data

  function submit(event) {
    event.preventDefault()
    setResult(null)
    action.run(async () => {
      const data = await request(path + '/ai/' + feature, {
        method: 'POST', body: feature === 'response' ? { tone } : undefined,
      })
      setResult(data)
    }, 'Suggestion ready')
  }

  return <Panel title="AI assist" icon={Sparkles} className="ai-panel">
    <Problem error={action.error} />
    <form className="form" onSubmit={submit}>
      <label>Task<select value={feature} disabled={action.busy}
        onChange={event => { setFeature(event.target.value); setResult(null) }}>
        <option value="resolution">Suggested resolution</option>
        <option value="response">Customer reply</option>
        <option value="classification">Complaint classification</option>
        <option value="sentiment">Sentiment analysis</option>
        <option value="priority">Priority prediction</option>
      </select></label>
      {feature === 'response' && <label>Tone<select value={tone} disabled={action.busy}
        onChange={event => { setTone(event.target.value); setResult(null) }}>
        {['EMPATHETIC', 'FORMAL', 'CONCISE'].map(value => <option key={value} value={value}>{label(value)}</option>)}
      </select></label>}
      <button className="button primary full" disabled={action.busy}><Sparkles size={16} />
        {action.busy ? 'Generating…' : 'Generate suggestion'}
      </button>
    </form>
    {action.busy && <div className="skeleton" aria-hidden="true"><i /><i /><i /></div>}
    {result && <div className="ai-result enter" aria-live="polite">
      <div className="meta"><Badge value={result.source} />{result.fallbackReason && <span>{label(result.fallbackReason)}</span>}</div>
      {stale && <p className="subtle">Ticket updated. Generate a fresh suggestion before using it.</p>}
      {draft.rationale && <><strong>{label(draft.categoryCode || draft.priority || draft.sentiment)}</strong><p>{draft.rationale}</p></>}
      {draft.steps && <><strong>{draft.summary}</strong>
        <ol>{draft.steps.map((step, index) => <li key={index}>{step}</li>)}</ol>
        {editable && !stale && <button type="button" className="button"
          onClick={() => useSummary(draft.steps.join('\n').slice(0, 2000))}>Use in resolution</button>}
      </>}
      {draft.body && <><strong>{draft.subject}</strong><p>{draft.body}</p>
        {editable && !stale && <button type="button" className="button" onClick={() => useReply(draft.body)}>Use in reply</button>}
      </>}
    </div>}
  </Panel>
}

function Triage({ ticket, update, busy }) {
  const categories = useData('/categories')

  return <Panel title="Triage" icon={Ticket}>
    <Problem error={categories.error} />
    <form className="form" onSubmit={event => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      const categoryCode = form.get('categoryCode')
      update('/triage', 'PATCH', {
        ...(categoryCode !== ticket.category.code ? { categoryCode } : {}),
        priority: form.get('priority'), reason: form.get('reason').trim(),
      }, 'Triage updated')
    }}>
      <label>Category<select name="categoryCode" defaultValue={ticket.category.code} required>
        <option value={ticket.category.code}>{ticket.category.name}</option>
        {categories.data?.filter(item => item.code !== ticket.category.code)
          .map(item => <option key={item.code} value={item.code}>{item.name}</option>)}
      </select></label>
      <label>Priority<select name="priority" defaultValue={ticket.priority}>
        {priorities.map(value => <option key={value} value={value}>{label(value)}</option>)}
      </select></label>
      <label>Reason<input name="reason" minLength={3} maxLength={255} required /></label>
      <button className="button" disabled={busy}>Save triage</button>
    </form>
  </Panel>
}

function Assignment({ path, ticket, update, busy }) {
  const agents = useData('/admin/agents', 10000)
  const candidates = useData(path + '/routing-candidates', 10000)
  const suggested = new Set(candidates.data?.map(item => item.agent.id) || [])

  return <Panel title="Assignment" icon={Headphones}>
    <Problem error={agents.error || candidates.error} />
    <form className="form" key={ticket.staff.assignee?.id || 'unassigned'} onSubmit={event => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      update('/assignment', 'PUT', {
        agentId: form.get('agentId') || null, reason: form.get('reason').trim(),
      }, 'Assignment saved')
    }}>
      <label>Agent<select name="agentId" defaultValue={ticket.staff.assignee?.id || ''} required={ticket.status !== 'OPEN'}>
        {ticket.status === 'OPEN' && <option value="">Unassigned</option>}
        {ticket.staff.assignee && !agents.data?.some(item => item.id === ticket.staff.assignee.id) &&
          <option value={ticket.staff.assignee.id}>{ticket.staff.assignee.displayName}</option>}
        {agents.data?.map(item => <option key={item.id} value={item.id}>
          {item.displayName} · {item.activeTicketCount}/{item.maxActiveTickets}{suggested.has(item.id) ? ' · Suggested' : ''}
        </option>)}
      </select></label>
      <label>Reason<input name="reason" minLength={3} maxLength={255} required /></label>
      <button className="button" disabled={busy}>Save assignment</button>
    </form>
  </Panel>
}

function AssignmentHistory({ path, version }) {
  const list = useData(path + '/assignments?version=' + version)
  return <Panel title="Assignment history">
    <Problem error={list.error} />
    {list.loading && <Loading />}
    {list.data?.length === 0 && <p className="subtle">No assignment yet.</p>}
    <ul className="compact-list">{list.data?.map((item, index) => <li key={item.assignedAt + index}>
      <span>{item.agentName} <small>{formatTime(item.assignedAt)}</small></span><Badge value={item.source} />
    </li>)}</ul>
  </Panel>
}

function utcDate(offset = 0) {
  const now = new Date()
  now.setUTCDate(now.getUTCDate() + offset)
  return now.toISOString().slice(0, 10)
}

function Overview({ user }) {
  const [range, setRange] = useState({ fromDate: utcDate(-13), toDate: utcDate(1) })
  const search = query(range)
  const summary = useData('/analytics/summary?' + search, 15000)
  const data = summary.data
  const download = format => '/api/v1/analytics/export?' + search + '&format=' + format

  return <>
    <section className="overview-hero">
      <div>
        <div className="eyebrow">SUPPORT OPERATIONS</div>
        <h1>Your support desk</h1>
        <p>Welcome, {user.displayName.split(' ')[0]}. See what came in, what’s waiting, and how your team is doing.</p>
        <Link to="/tickets" className="button light">Open ticket queue <ArrowRight size={16} /></Link>
      </div>
      <div className="hero-art" aria-hidden="true"><span /><span /><span /><i><Activity size={42} /></i></div>
    </section>
    <section className="report-toolbar" aria-label="Report controls">
      <form className="date-form" onSubmit={event => {
        event.preventDefault()
        setRange(Object.fromEntries(new FormData(event.currentTarget)))
      }}>
        <label>From (UTC)<input type="date" name="fromDate" defaultValue={range.fromDate} required /></label>
        <label>Before (UTC)<input type="date" name="toDate" defaultValue={range.toDate} required /></label>
        <button className="button">Apply dates</button>
      </form>
      <div className="actions">
        <a className="button" href={download('csv')}><ArrowDownToLine size={16} />CSV</a>
        <a className="button" href={download('json')}><ArrowDownToLine size={16} />JSON</a>
      </div>
    </section>
    <Problem error={summary.error} />
    {summary.loading && <Loading text="Loading analytics…" />}
    {data && <>
      <div className="metric-grid">
        <Metric icon={Ticket} color="violet" caption="New tickets" value={data.createdTickets} />
        <Metric icon={Inbox} color="blue" caption="Open backlog" value={data.openBacklog} />
        <Metric icon={Clock3} color="green" caption="On-time resolutions"
          value={data.resolutionSlaMetPct == null ? '—' : data.resolutionSlaMetPct + '%'} />
        <Metric icon={Star} color="high" caption="Customer rating"
          value={data.averageRating == null ? '—' : Number(data.averageRating).toFixed(1) + ' / 5'} />
      </div>
      <div className="report-grid">
        <Panel title="Ticket volume" icon={Activity}><Volume series={data.daily} /></Panel>
        <Panel title="Agent workload" icon={Headphones}>
          {data.agentWorkload.length === 0 && <Empty text="No agents are configured." />}
          <ul className="workload">{data.agentWorkload.map(row => <li key={row.agent.id}>
            <span className="avatar">{row.agent.displayName[0]}</span>
            <div>
              <header><strong>{row.agent.displayName}</strong><span>{row.activeTicketCount}/{row.maxActiveTickets}</span></header>
              <progress aria-label={row.agent.displayName + ' workload'} value={row.activeTicketCount} max={row.maxActiveTickets} />
            </div>
          </li>)}</ul>
        </Panel>
        <Panel title="Categories" icon={Inbox}>
          <ul className="compact-list">{data.categoryVolume.map(row => <li key={row.categoryCode}>
            <span>{label(row.categoryCode)}</span><strong>{row.count}</strong>
          </li>)}</ul>
          {!data.categoryVolume.length && <Empty text="No tickets in this period." />}
        </Panel>
        <Panel title="AI activity" icon={Sparkles}>
          <ul className="compact-list">{data.aiUsage.map(row => <li key={row.feature}>
            <span>{label(row.feature)}</span><span>{row.modelRuns} model · {row.fallbackRuns} fallback</span>
          </li>)}</ul>
          {!data.aiUsage.length && <Empty text="No AI runs in this period." />}
        </Panel>
      </div>
    </>}
  </>
}

function Metric({ icon: Icon, color, caption, value }) {
  return <div className="metric panel">
    <span className={'metric-icon ' + color}><Icon size={19} /></span>
    <small>{caption}</small><strong>{value}</strong>
  </div>
}

function Volume({ series }) {
  const [table, setTable] = useState(false)
  const limit = Math.max(1, ...series.map(day => Math.max(day.createdTickets, day.resolvedCycles)))
  const marks = Array.from({ length: Math.min(5, series.length) }, (_, index) =>
    Math.round(index * (series.length - 1) / Math.max(1, Math.min(5, series.length) - 1)))

  return <>
    <div className="chart-top">
      <div className="legend"><span>New</span><span>Resolved</span></div>
      <button type="button" className="button small" onClick={() => setTable(!table)}>
        {table ? 'Show chart' : 'Show data'}
      </button>
    </div>
    {table ? <div className="data-table"><table>
      <thead><tr><th>Date</th><th>New</th><th>Resolved</th></tr></thead>
      <tbody>{series.map(day => <tr key={day.date}><td>{day.date}</td><td>{day.createdTickets}</td><td>{day.resolvedCycles}</td></tr>)}</tbody>
    </table></div> : <div className="volume" aria-hidden="true">
      <div className="chart-bars">{series.map(day => <div key={day.date}
        title={day.date + ' · ' + day.createdTickets + ' new · ' + day.resolvedCycles + ' resolved'}>
        <i style={{ height: day.createdTickets / limit * 100 + '%' }} />
        <i style={{ height: day.resolvedCycles / limit * 100 + '%' }} />
      </div>)}</div>
      <div className="chart-dates">{marks.map(index => <span key={series[index].date}>{series[index].date.slice(5)}</span>)}</div>
    </div>}
  </>
}

function Alerts({ notify }) {
  const [acknowledged, setAcknowledged] = useState(false)
  const alerts = useData('/alerts?' + query({ acknowledged }), 12000)
  const action = useAction(notify)

  return <>
    <PageTitle eyebrow="SERVICE LEVELS" title="SLA alerts" subtitle="Review urgent requests and approaching or missed deadlines." />
    <Panel title="Alert inbox" icon={Bell}>
      <label className="check"><input type="checkbox" checked={acknowledged}
        onChange={event => setAcknowledged(event.target.checked)} />Show acknowledged</label>
      <Problem error={alerts.error || action.error} />
      {alerts.loading && <Loading />}
      {alerts.data?.items.length === 0 && <Empty text="No alerts in this view." />}
      <ul className="alert-list">{alerts.data?.items.map(item => <li key={item.id}>
        <span className={'metric-icon ' + (item.type.includes('BREACHED') ? 'urgent' : 'high')}><Bell size={17} /></span>
        <div><strong>{label(item.type)}</strong><Link to={'/tickets/' + item.ticketKey}>{item.ticketKey}</Link>
          <small>{formatTime(item.createdAt)}</small></div>
        {item.acknowledgedAt ? <Badge value="ACKNOWLEDGED" /> : <button type="button" className="button" disabled={action.busy}
          onClick={() => action.run(async () => {
            await request('/alerts/' + item.id + '/acknowledgement', { method: 'PUT' })
            alerts.refresh()
          }, 'Alert acknowledged')}>Acknowledge</button>}
      </li>)}</ul>
    </Panel>
  </>
}

function Team({ notify }) {
  const team = useData('/admin/agents')
  const categories = useData('/admin/categories')
  const [selected, setSelected] = useState('')
  const category = categories.data?.find(item => item.code === selected)

  return <>
    <PageTitle eyebrow="ADMINISTRATION" title="Team & routing" subtitle="Manage routing capacity, category skills, and support playbooks." />
    <div className="report-grid">
      <Panel title="Routing profiles" icon={Headphones}>
        <Problem error={team.error} />
        {team.loading && <Loading />}
        {team.data?.map(agent => <AgentSettings key={agent.id} agent={agent} categories={categories.data || []}
          notify={notify} refresh={team.refresh} />)}
        {team.data?.length === 0 && <Empty text="No agents are configured." />}
      </Panel>
      <Panel title="Category settings" icon={Settings2}>
        <Problem error={categories.error} />
        {categories.loading && <Loading />}
        {categories.data && <>
          <label>Category<select value={selected} onChange={event => setSelected(event.target.value)}>
            <option value="">Add a category</option>
            {categories.data.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}
          </select></label>
          {(!selected || category) && <CategorySettings key={selected} category={category} notify={notify}
            saved={code => { categories.refresh(); setSelected(code); team.refresh() }} />}
        </>}
      </Panel>
    </div>
  </>
}

function AgentSettings({ agent, categories, notify, refresh }) {
  const action = useAction(notify)
  const skills = Object.fromEntries(agent.skills.map(item => [item.categoryCode, item.proficiency]))

  function submit(event) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    action.run(async () => {
      await request('/admin/agents/' + agent.id + '/routing', { method: 'PUT', body: {
        routingAvailable: form.has('available'),
        maxActiveTickets: Number(form.get('load')),
        skills: categories.filter(item => item.enabled && Number(form.get(item.code)) > 0)
          .map(item => ({ categoryCode: item.code, proficiency: Number(form.get(item.code)) })),
      } })
      refresh()
    }, 'Routing profile saved')
  }

  return <details className="settings">
    <summary><strong>{agent.displayName}</strong><small>{agent.activeTicketCount} active</small></summary>
    <Problem error={action.error} />
    <form className="form" onSubmit={submit}>
      <label className="check"><input name="available" type="checkbox" defaultChecked={agent.routingAvailable} />
        Available for automatic routing</label>
      <label>Maximum active tickets<input name="load" type="number" min="1" max="100" defaultValue={agent.maxActiveTickets} required /></label>
      <div className="skills">{categories.filter(item => item.enabled).map(item => <label key={item.code}>{item.name}
        <select name={item.code} defaultValue={skills[item.code] || 0}>
          <option value="0">No skill</option><option value="1">Level 1</option>
          <option value="2">Level 2</option><option value="3">Level 3</option>
        </select></label>)}</div>
      <button className="button primary" disabled={action.busy}>Save routing</button>
    </form>
  </details>
}

function CategorySettings({ category, notify, saved }) {
  const action = useAction(notify)

  function submit(event) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const code = category?.code || form.get('code').trim().toUpperCase()
    const body = {
      code, name: form.get('name').trim(), description: form.get('description').trim(),
      enabled: code === 'UNCATEGORIZED' || form.has('enabled'),
      triageKeywords: form.get('keywords').split(',').map(item => item.trim()).filter(Boolean),
      resolutionPlaybook: form.get('playbook').trim(),
    }
    action.run(async () => {
      await request('/admin/categories' + (category ? '/' + encodeURIComponent(code) : ''), {
        method: category ? 'PUT' : 'POST', body,
      })
      saved(code)
    }, 'Category saved')
  }

  return <form className="form category-form" onSubmit={submit}>
    <Problem error={action.error} />
    {!category && <label>Code<input name="code" placeholder="EXAMPLE_CODE" pattern="[A-Z][A-Z0-9_]{1,39}" required /></label>}
    <label>Name<input name="name" defaultValue={category?.name} maxLength={100} required /></label>
    <label>Description<textarea name="description" defaultValue={category?.description} rows={2} maxLength={500} required /></label>
    <label>Keywords<input name="keywords" defaultValue={category?.triageKeywords.join(', ')} placeholder="Separate keywords with commas" /></label>
    <label>Resolution playbook<textarea name="playbook" defaultValue={category?.resolutionPlaybook} rows={4} maxLength={5000} required /></label>
    <label className="check"><input name="enabled" type="checkbox" defaultChecked={category?.enabled ?? true}
      disabled={category?.code === 'UNCATEGORIZED'} />Enabled</label>
    <button className="button primary" disabled={action.busy}>Save category</button>
  </form>
}

createRoot(document.getElementById('root')).render(<BrowserRouter><App /></BrowserRouter>)