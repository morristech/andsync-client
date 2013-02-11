/*
 * Copyright 2012 Tim Roes <tim.roes@inovex.de>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.inovex.andsync;

import java.net.MalformedURLException;
import java.net.URL;

/**
 *
 * @author Tim Roes <tim.roes@inovex.de>
 */
public class Config {
	
	private URL mUrl;
	
	private Config(Builder builder) {
		this.mUrl = builder.mUrl;
	}

	public URL getUrl() {
		return mUrl;
	}
	
	public static class Builder {
		
		private URL mUrl;
		
		public Builder(URL url) {
			this.mUrl = url;
		}
		
		public Builder(String url) throws MalformedURLException {
			this.mUrl = new URL(url);
		}
			
		public Config build() {
			return new Config(this);
		}
		
	}
	
}