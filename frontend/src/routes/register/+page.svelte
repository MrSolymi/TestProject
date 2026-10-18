<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { page } from '$app/state';
	import { ApiError } from '#lib/api.js';
	import { auth } from '#lib/auth.svelte.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import * as Card from '#lib/components/ui/card/index.js';
	import { Input } from '#lib/components/ui/input/index.js';
	import { Label } from '#lib/components/ui/label/index.js';

	let username = $state('');
	let password = $state('');
	// Invite links look like /register?invite=<token>.
	let inviteToken = $state(page.url.searchParams.get('invite') ?? '');
	let error = $state('');
	let fieldErrors = $state<Record<string, string>>({});
	let submitting = $state(false);

	async function submit(event: SubmitEvent) {
		event.preventDefault();
		error = '';
		fieldErrors = {};
		submitting = true;
		try {
			await auth.register(username, password, inviteToken.trim());
			await goto(resolve('/'));
		} catch (e) {
			if (e instanceof ApiError) {
				error = e.message;
				fieldErrors = e.fieldErrors;
			} else {
				error = 'Could not reach the server';
			}
		} finally {
			submitting = false;
		}
	}
</script>

<main class="flex min-h-screen items-center justify-center p-4">
	<Card.Root class="w-full max-w-sm">
		<Card.Header>
			<Card.Title>Register</Card.Title>
			<Card.Description>You need an invite from an existing user.</Card.Description>
		</Card.Header>
		<form onsubmit={submit}>
			<Card.Content class="grid gap-4">
				<div class="grid gap-2">
					<Label for="invite">Invite token</Label>
					<Input id="invite" autocomplete="off" required bind:value={inviteToken} />
				</div>
				<div class="grid gap-2">
					<Label for="username">Username</Label>
					<Input id="username" autocomplete="username" required bind:value={username} />
					{#if fieldErrors.username}
						<p class="text-sm text-destructive">{fieldErrors.username}</p>
					{/if}
				</div>
				<div class="grid gap-2">
					<Label for="password">Password</Label>
					<Input
						id="password"
						type="password"
						autocomplete="new-password"
						required
						bind:value={password}
					/>
					{#if fieldErrors.password}
						<p class="text-sm text-destructive">Password {fieldErrors.password}</p>
					{/if}
				</div>
				{#if error}
					<p class="text-sm text-destructive" role="alert">{error}</p>
				{/if}
			</Card.Content>
			<Card.Footer class="mt-6 flex-col gap-3">
				<Button type="submit" class="w-full" disabled={submitting}>Create account</Button>
				<p class="text-sm text-muted-foreground">
					Already registered?

					<a href={resolve('login')} class="underline underline-offset-4">Log in</a>
				</p>
			</Card.Footer>
		</form>
	</Card.Root>
</main>
