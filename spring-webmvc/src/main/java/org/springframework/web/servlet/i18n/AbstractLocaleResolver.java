/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.i18n;

import java.util.Locale;

import org.springframework.lang.Nullable;
import org.springframework.web.servlet.LocaleResolver;

/**
 * 实现 LocaleResolver 接口的抽象基类
 * 提供对默认语言环境的支持
 * Abstract base class for {@link LocaleResolver} implementations.
 * Provides support for a default locale.
 *
 * @author Juergen Hoeller
 * @since 1.2.9
 * @see #setDefaultLocale
 */
public abstract class AbstractLocaleResolver implements LocaleResolver {

	@Nullable
	private Locale defaultLocale;


	/**
	 * Set a default Locale that this resolver will return if no other locale found.
	 */
	public void setDefaultLocale(@Nullable Locale defaultLocale) {
		this.defaultLocale = defaultLocale;
	}

	/**
	 * Return the default Locale that this resolver is supposed to fall back to, if any.
	 */
	@Nullable
	protected Locale getDefaultLocale() {
		return this.defaultLocale;
	}

}
