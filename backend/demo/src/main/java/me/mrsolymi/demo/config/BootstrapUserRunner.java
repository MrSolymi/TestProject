package me.mrsolymi.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import me.mrsolymi.demo.user.User;
import me.mrsolymi.demo.user.UserRepository;

/** Creates the first user from configuration, since registering requires an invite from an existing user. */
@Component
@RequiredArgsConstructor
public class BootstrapUserRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BootstrapUserRunner.class);

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AppProperties props;

	@Override
	public void run(ApplicationArguments args) {
		if (users.count() > 0) {
			return;
		}
		String username = props.bootstrap().username();
		String password = props.bootstrap().password();
		if (username == null || username.isBlank() || password == null || password.isBlank()) {
			log.warn("No users exist and APP_BOOTSTRAP_USERNAME / APP_BOOTSTRAP_PASSWORD are not set; nobody can register");
			return;
		}
		users.save(new User(username, passwordEncoder.encode(password), null));
		log.info("Created bootstrap user '{}'", username);
	}

}
