<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { ApiError } from '#lib/api.js';
	import { auth } from '#lib/auth.svelte.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import * as Card from '#lib/components/ui/card/index.js';
	import { Input } from '#lib/components/ui/input/index.js';

	let inviteLink = $state('');
	let copied = $state(false);
	let error = $state('');

	async function createInvite() {
		error = '';
		copied = false;
		try {
			const invite = await auth.createInvite();
			inviteLink = `${location.origin}/register?invite=${encodeURIComponent(invite.token)}`;
		} catch (e) {
			error = e instanceof ApiError ? e.message : 'Could not reach the server';
		}
	}

	async function copy() {
		await navigator.clipboard.writeText(inviteLink);
		copied = true;
	}

	async function logout() {
		await auth.logout();
		await goto(resolve('login'));
	}
</script>

<main class="flex min-h-screen items-center justify-center p-4">
	<Card.Root class="w-full max-w-md">
		<Card.Header>
			<Card.Title class="text-2xl">Hello, {auth.user?.username}!</Card.Title>
			<Card.Description>You are logged in.</Card.Description>
		</Card.Header>
		<Card.Content class="grid gap-3">
			<div>
				<Button variant="outline" onclick={createInvite}>Create invite link</Button>
			</div>
			{#if inviteLink}
				<div class="flex gap-2">
					<Input
						readonly
						value={inviteLink}
						aria-label="Invite link"
						onfocus={(e) => e.currentTarget.select()}
					/>
					<Button variant="secondary" onclick={copy}>{copied ? 'Copied' : 'Copy'}</Button>
				</div>
				<p class="text-sm text-muted-foreground">Single use. Expires in 7 days.</p>
			{/if}
			{#if error}
				<p class="text-sm text-destructive" role="alert">{error}</p>
			{/if}
		</Card.Content>
		<Card.Footer>
			<Button onclick={logout}>Log out</Button>
		</Card.Footer>
	</Card.Root>
</main>
