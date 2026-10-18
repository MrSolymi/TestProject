import { api, ApiError } from './api';

export type User = { id: string; username: string };
export type Invite = { token: string; expiresAt: string };

class Auth {
	user = $state<User | null>(null);
	loaded = false;

	/** Asks the backend who the session cookie belongs to. 401 simply means "not logged in". */
	async refresh() {
		try {
			this.user = await api<User>('/api/auth/me');
		} catch (e) {
			if (!(e instanceof ApiError && e.status === 401)) throw e;
			this.user = null;
		}
		this.loaded = true;
	}

	async login(username: string, password: string) {
		this.user = await api<User>('/api/auth/login', {
			method: 'POST',
			body: { username, password }
		});
	}

	async register(username: string, password: string, inviteToken: string) {
		this.user = await api<User>('/api/auth/register', {
			method: 'POST',
			body: { username, password, inviteToken }
		});
	}

	async logout() {
		await api('/api/auth/logout', { method: 'POST' });
		this.user = null;
	}

	createInvite() {
		return api<Invite>('/api/invites', { method: 'POST' });
	}
}

export const auth = new Auth();
