import { useState, useCallback, useEffect } from 'react';
import { gwApi } from '../api/client';
import { Icon } from '../components/Icon';
import { Modal } from '../components/Modal';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { useToast } from '../context/ToastContext';
import { countryFlag, val } from '../utils/format';

interface GwServer {
  id?: string;
  name?: string;
  country: string;
  ip_address: string;
  ssh_port: number;
  ssh_username: string;
  ssh_password: string;
  proxy_host: string;
  proxy_port: number;
  proxy_scheme: string;
  payload: string;
  sni: string;
  ssh_hostkey: string;
  recommend: boolean;
  priority: number;
  created_at: number;
  premium: boolean;
  state: string;
  status: boolean;
  country_code: string;
}

interface EditForm {
  ip_address: string;
  name: string;
  country: string;
  state: string;
  country_code: string;
  ssh_port: string;
  ssh_username: string;
  ssh_password: string;
  proxy_host: string;
  proxy_port: string;
  proxy_scheme: string;
  sni: string;
  payload: string;
  priority: string;
  premium: boolean;
  status: boolean;
}

const DEFAULT_PAYLOAD = 'GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf]';

function emptyForm(): EditForm {
  return {
    ip_address: '',
    name: '',
    country: '',
    state: '',
    country_code: '',
    ssh_port: '22',
    ssh_username: 'gw',
    ssh_password: '',
    proxy_host: '',
    proxy_port: '80',
    proxy_scheme: 'http',
    sni: '',
    payload: DEFAULT_PAYLOAD,
    priority: '0',
    premium: false,
    status: true,
  };
}

export function ServersGw() {
  const toast = useToast();
  const [servers, setServers] = useState<GwServer[]>([]);
  const [loading, setLoading] = useState(true);
  const [editTarget, setEditTarget] = useState<EditForm | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<GwServer | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await gwApi.all();
      if (r.success) setServers(r.data || []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const toggleField = async (s: GwServer, field: 'status' | 'premium') => {
    const r = await gwApi.update({
      ip_address: s.ip_address,
      [field]: String(!s[field]),
    });
    if (r.success) {
      toast.success(field === 'status' ? (s.status ? 'Выключен' : 'Включён') : (s.premium ? 'Премиум снят' : 'Премиум включён'));
      load();
    } else {
      toast.error('Ошибка', r.message);
    }
  };

  const openEdit = (s: GwServer) => {
    setIsNew(false);
    setEditTarget({
      ip_address: s.ip_address,
      name: val(s.name, '') === '—' ? '' : (s.name || ''),
      country: s.country === '0' ? '' : s.country,
      state: s.state === '0' ? '' : s.state,
      country_code: s.country_code === '0' ? '' : s.country_code,
      ssh_port: String(s.ssh_port ?? 22),
      ssh_username: s.ssh_username || 'gw',
      ssh_password: s.ssh_password || '',
      proxy_host: s.proxy_host || '',
      proxy_port: String(s.proxy_port ?? 80),
      proxy_scheme: s.proxy_scheme || 'http',
      sni: s.sni || '',
      payload: s.payload || '',
      priority: String(s.priority ?? 0),
      premium: s.premium,
      status: s.status,
    });
  };

  const openCreate = () => {
    setIsNew(true);
    setEditTarget(emptyForm());
  };

  const handleSave = async () => {
    if (!editTarget) return;
    if (!editTarget.ip_address.trim()) {
      toast.error('SSH-хост обязателен');
      return;
    }
    setSaving(true);
    try {
      const fields: Record<string, any> = {
        ip_address: editTarget.ip_address.trim(),
        name: editTarget.name,
        country: editTarget.country,
        state: editTarget.state,
        country_code: editTarget.country_code.toUpperCase(),
        ssh_port: editTarget.ssh_port,
        ssh_username: editTarget.ssh_username,
        ssh_password: editTarget.ssh_password,
        proxy_host: editTarget.proxy_host,
        proxy_port: editTarget.proxy_port,
        proxy_scheme: editTarget.proxy_scheme,
        sni: editTarget.sni,
        payload: editTarget.payload,
        priority: editTarget.priority,
        premium: String(editTarget.premium),
        status: String(editTarget.status),
      };
      const r = isNew ? await gwApi.create(fields) : await gwApi.update(fields);
      if (r.success) {
        toast.success(isNew ? 'Сервер добавлен' : 'Сервер обновлён');
        setEditTarget(null);
        load();
      } else {
        toast.error('Ошибка', r.message);
      }
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setSaving(true);
    try {
      const r = await gwApi.delete(deleteTarget.ip_address);
      if (r.success) {
        toast.success('Сервер удалён');
        setDeleteTarget(null);
        load();
      } else {
        toast.error('Ошибка', r.message);
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="flex-col gap-6">
      <div className="page-header">
        <div>
          <div className="page-title">Серверы GW</div>
          <div className="page-subtitle">SSH over WebSocket · {servers.length} серверов</div>
        </div>
        <div className="flex gap-2">
          <button className="btn btn-ghost btn-sm" onClick={load}>
            <Icon name="refresh" size={16} /> Обновить
          </button>
          <button className="btn btn-primary btn-sm" onClick={openCreate}>
            <Icon name="plus" size={16} /> Добавить
          </button>
        </div>
      </div>

      <div className="card">
        <div className="card-body card-body-flush">
          {loading ? (
            <div className="loading-center"><span className="spinner" /> Загрузка…</div>
          ) : servers.length === 0 ? (
            <div className="empty-state">
              <Icon name="serverGw" size={48} className="empty-state-icon" />
              <div className="empty-state-title">Серверов нет</div>
            </div>
          ) : (
            <div className="table-wrap">
              <table className="table table-mobile">
                <thead>
                  <tr>
                    <th>Страна</th>
                    <th>SSH-хост</th>
                    <th>Прокси (CDN)</th>
                    <th>Статус</th>
                    <th>Премиум</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {servers.map((s) => (
                    <tr key={s.ip_address}>
                      <td data-label="Страна">
                        <div className="flex items-center gap-2">
                          <span style={{ fontSize: 18 }}>{countryFlag(s.country_code)}</span>
                          <span style={{ fontWeight: 600 }}>{val(s.country, 'N/A')}</span>
                        </div>
                      </td>
                      <td data-label="SSH-хост" className="mono">{s.ip_address}:{s.ssh_port}</td>
                      <td data-label="Прокси (CDN)" className="mono text-muted">{val(s.proxy_host, '—')}</td>
                      <td data-label="Статус">
                        <div className="flex items-center gap-2">
                          <label className="switch" title={s.status ? 'Выключить' : 'Включить'}>
                            <input type="checkbox" checked={s.status} onChange={() => toggleField(s, 'status')} />
                            <span className="switch-slider" />
                          </label>
                          <span className={`badge ${s.status ? 'badge-success' : 'badge-danger'}`}>
                            {s.status ? 'Вкл' : 'Выкл'}
                          </span>
                        </div>
                      </td>
                      <td data-label="Премиум">
                        <label className="switch" title={s.premium ? 'Снять Премиум' : 'Включить Премиум'}>
                          <input type="checkbox" checked={s.premium} onChange={() => toggleField(s, 'premium')} />
                          <span className="switch-slider" />
                        </label>
                      </td>
                      <td data-label="" className="col-actions">
                        <div className="flex gap-1 justify-end">
                          <button className="btn btn-ghost btn-icon btn-sm" onClick={() => openEdit(s)} title="Редактировать">
                            <Icon name="edit" size={16} />
                          </button>
                          <button className="btn btn-ghost btn-icon btn-sm" onClick={() => setDeleteTarget(s)} title="Удалить" style={{ color: 'var(--danger-fg)' }}>
                            <Icon name="trash" size={16} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Create/Edit modal */}
      <Modal
        open={!!editTarget}
        onClose={() => setEditTarget(null)}
        title={isNew ? 'Добавление GW-сервера' : 'Редактирование GW-сервера'}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setEditTarget(null)}>Отмена</button>
            <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
              {saving && <span className="spinner" style={{ width: 16, height: 16 }} />}
              Сохранить
            </button>
          </>
        }
      >
        {editTarget && (
          <div className="flex-col gap-4">
            <div className="flex gap-4">
              <div className="input-group grow">
                <label className="input-label">SSH-хост *</label>
                <input className="input mono" value={editTarget.ip_address} disabled={!isNew}
                  onChange={(e) => setEditTarget({ ...editTarget, ip_address: e.target.value })}
                  placeholder="1.2.3.4 или node.example.com" />
              </div>
              <div className="input-group" style={{ width: 110 }}>
                <label className="input-label">SSH-порт</label>
                <input className="input mono" value={editTarget.ssh_port}
                  onChange={(e) => setEditTarget({ ...editTarget, ssh_port: e.target.value.replace(/\D/g, '') })} />
              </div>
            </div>
            <div className="flex gap-4">
              <div className="input-group grow">
                <label className="input-label">SSH-пользователь</label>
                <input className="input mono" value={editTarget.ssh_username}
                  onChange={(e) => setEditTarget({ ...editTarget, ssh_username: e.target.value })} placeholder="gw" />
              </div>
              <div className="input-group grow">
                <label className="input-label">SSH-пароль</label>
                <input className="input mono" type="password" value={editTarget.ssh_password}
                  onChange={(e) => setEditTarget({ ...editTarget, ssh_password: e.target.value })} />
              </div>
            </div>
            <div className="flex gap-4">
              <div className="input-group grow">
                <label className="input-label">Proxy host (CDN)</label>
                <input className="input mono" value={editTarget.proxy_host}
                  onChange={(e) => setEditTarget({ ...editTarget, proxy_host: e.target.value })}
                  placeholder="cdn.example.com" />
              </div>
              <div className="input-group" style={{ width: 110 }}>
                <label className="input-label">Proxy порт</label>
                <input className="input mono" value={editTarget.proxy_port}
                  onChange={(e) => setEditTarget({ ...editTarget, proxy_port: e.target.value.replace(/\D/g, '') })} />
              </div>
              <div className="input-group" style={{ width: 120 }}>
                <label className="input-label">Схема</label>
                <select className="input" value={editTarget.proxy_scheme}
                  onChange={(e) => setEditTarget({ ...editTarget, proxy_scheme: e.target.value })}>
                  <option value="http">http</option>
                  <option value="https">https</option>
                </select>
              </div>
            </div>
            <div className="flex gap-4">
              <div className="input-group grow">
                <label className="input-label">SNI</label>
                <input className="input mono" value={editTarget.sni}
                  onChange={(e) => setEditTarget({ ...editTarget, sni: e.target.value })}
                  placeholder="для TLS/CDN-фронта" />
              </div>
              <div className="input-group" style={{ width: 110 }}>
                <label className="input-label">Приоритет</label>
                <input className="input mono" value={editTarget.priority}
                  onChange={(e) => setEditTarget({ ...editTarget, priority: e.target.value.replace(/\D/g, '') })} />
              </div>
            </div>
            <div className="input-group">
              <label className="input-label">Payload</label>
              <textarea className="input mono" rows={4} value={editTarget.payload}
                onChange={(e) => setEditTarget({ ...editTarget, payload: e.target.value })}
                placeholder={DEFAULT_PAYLOAD} />
            </div>
            <div className="flex gap-4">
              <div className="input-group grow">
                <label className="input-label">Название</label>
                <input className="input" value={editTarget.name}
                  onChange={(e) => setEditTarget({ ...editTarget, name: e.target.value })} placeholder="DE-Frankfurt-01" />
              </div>
              <div className="input-group grow">
                <label className="input-label">Страна</label>
                <input className="input" value={editTarget.country}
                  onChange={(e) => setEditTarget({ ...editTarget, country: e.target.value })} placeholder="Германия" />
              </div>
              <div className="input-group" style={{ width: 110 }}>
                <label className="input-label">Код</label>
                <input className="input mono" value={editTarget.country_code}
                  onChange={(e) => setEditTarget({ ...editTarget, country_code: e.target.value.toUpperCase() })}
                  placeholder="DE" maxLength={2} />
              </div>
            </div>
            <div className="flex gap-4">
              <div className="input-group grow">
                <label className="input-label">Регион / Город</label>
                <input className="input" value={editTarget.state}
                  onChange={(e) => setEditTarget({ ...editTarget, state: e.target.value })} placeholder="Frankfurt" />
              </div>
            </div>
            <div className="flex gap-6">
              <div className="flex items-center gap-3">
                <label className="switch">
                  <input type="checkbox" checked={editTarget.status} onChange={(e) => setEditTarget({ ...editTarget, status: e.target.checked })} />
                  <span className="switch-slider" />
                </label>
                <span style={{ fontWeight: 600 }}>Статус (вкл/выкл)</span>
              </div>
              <div className="flex items-center gap-3">
                <label className="switch">
                  <input type="checkbox" checked={editTarget.premium} onChange={(e) => setEditTarget({ ...editTarget, premium: e.target.checked })} />
                  <span className="switch-slider" />
                </label>
                <span style={{ fontWeight: 600 }}>Премиум</span>
              </div>
            </div>
          </div>
        )}
      </Modal>

      <ConfirmDialog
        open={!!deleteTarget}
        title="Удалить сервер?"
        message={<>GW-сервер <b>{deleteTarget?.country}</b> ({deleteTarget?.ip_address}) будет удалён.</>}
        confirmText="Удалить"
        danger
        loading={saving}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
