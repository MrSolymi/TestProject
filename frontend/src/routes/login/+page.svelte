<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { ApiError } from '#lib/api.js';
	import { auth } from '#lib/auth.svelte.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import * as Card from '#lib/components/ui/card/index.js';
	import { Input } from '#lib/components/ui/input/index.js';
	import { Label } from '#lib/components/ui/label/index.js';

	let username = $state('');
	let password = $state('');
	let error = $state('');
	let submitting = $state(false);

	async function submit(event: SubmitEvent) {
		event.preventDefault();
		error = '';
		submitting = true;
		try {
			await auth.login(username, password);
			await goto(resolve('/'));
		} catch (e) {
			error = e instanceof ApiError ? e.message : 'Could not reach the server';
		} finally {
			submitting = false;
		}
	}
</script>

<main class="flex min-h-screen items-center justify-center p-4">
	<Card.Root class="w-full max-w-sm">
		<Card.Header>
			<Card.Title>Log in</Card.Title>
			<Card.Description>Enter your username and password.</Card.Description>
		</Card.Header>
		<form onsubmit={submit}>
			<Card.Content class="grid gap-4">
				<div class="grid gap-2">
					<Label for="username">Username</Label>
					<Input id="username" autocomplete="username" required bind:value={username} />
				</div>
				<div class="grid gap-2">
					<Label for="password">Password</Label>
					<Input
						id="password"
						type="password"
						autocomplete="current-password"
						required
						bind:value={password}
					/>
				</div>
				{#if error}
					<p class="text-sm text-destructive" role="alert">{error}</p>
				{/if}
			</Card.Content>
			<Card.Footer class="mt-6 flex-col gap-3">
				<Button type="submit" class="w-full" disabled={submitting}>Log in</Button>
				<p class="text-sm text-muted-foreground">
					Have an invite?

					<a href={resolve('register')} class="underline underline-offset-4">Register</a>
				</p>
			</Card.Footer>
		</form>
	</Card.Root>
</main>
