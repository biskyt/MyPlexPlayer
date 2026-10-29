# Review and link logos

The details pages show each review score with its source's logo, and More info's links show their sites' logos.
They are the companies' own marks, drawn as vector drawables (`app/src/main/res/drawable/logo_*.xml`), with only the
detail visible at the size they are drawn (20 dp tall; TMDB's, one long line, sits in the middle of a viewport twice its height, so it weighs about the same as the others). They are used only to name the source of a score
or the site a link opens. Nothing in the app suggests the companies make or endorse it.

| Drawable | Logo | Taken from (2026-10-29) |
| --- | --- | --- |
| `logo_imdb` | IMDb | Wikimedia Commons, `File:IMDB_Logo_2016.svg` |
| `logo_tmdb` | TMDB (short) | TMDB's logos page, https://www.themoviedb.org/about/logos-attribution (`blue_short`). TMDB asks apps using its data to show its logo. |
| `logo_tvdb` | TheTVDB | TheTVDB's site, https://thetvdb.com/images/logo.svg |
| `logo_rt_fresh` | Rotten Tomatoes, fresh tomato (critics, 60% and over) | Wikimedia Commons, `File:Rotten_Tomatoes.svg` (public domain) |
| `logo_rt_rotten` | Rotten Tomatoes, rotten splat (critics, under 60%) | Wikimedia Commons, `File:Rotten_Tomatoes_rotten.svg` (public domain) |
| `logo_rt_upright` | Rotten Tomatoes, upright popcorn (audience, 60% and over) | Wikimedia Commons, `File:Rotten_Tomatoes_positive_audience.svg` |
| `logo_rt_spilled` | Rotten Tomatoes, spilled popcorn (audience, under 60%) | Wikimedia Commons, `File:Rotten_Tomatoes_negative_audience.svg` |

Converted with `svg2vectordrawable` (1 decimal place). Invisible duplicate paths were removed. TMDB's gradient was added
by hand: the converter doesn't read the CSS that sets it.

IMDb, TMDB, TheTVDB and Rotten Tomatoes are trademarks of their owners.
