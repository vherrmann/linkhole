// Package titles fetches page titles in the background.
package titles

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"golang.org/x/net/html"
)

const (
	timeout  = 10 * time.Second
	maxBytes = 1 << 20
)

// Fetcher resolves a URL to a human-readable title.
type Fetcher struct {
	Client *http.Client
	// OEmbedURL is the YouTube oEmbed endpoint; overridable for tests.
	OEmbedURL string
}

func NewFetcher() *Fetcher {
	return &Fetcher{
		Client:    &http.Client{Timeout: timeout},
		OEmbedURL: "https://www.youtube.com/oembed",
	}
}

func isYouTube(host string) bool {
	host = strings.TrimPrefix(strings.ToLower(host), "www.")
	return host == "youtube.com" || host == "m.youtube.com" || host == "music.youtube.com" ||
		host == "youtu.be" || host == "youtube-nocookie.com"
}

// Fetch returns the title of rawURL, or an error.
func (f *Fetcher) Fetch(ctx context.Context, rawURL string) (string, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return "", err
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", fmt.Errorf("unsupported scheme %q", u.Scheme)
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if isYouTube(u.Hostname()) {
		if t, err := f.oembed(ctx, rawURL); err == nil && t != "" {
			return t, nil
		}
		// fall through to the HTML title
	}
	return f.htmlTitle(ctx, rawURL)
}

func (f *Fetcher) get(ctx context.Context, u string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "the-next-thing/1.0 (+title fetcher)")
	req.Header.Set("Accept", "text/html,application/json;q=0.9,*/*;q=0.5")
	resp, err := f.Client.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	return resp, nil
}

func (f *Fetcher) oembed(ctx context.Context, rawURL string) (string, error) {
	q := url.Values{"url": {rawURL}, "format": {"json"}}
	resp, err := f.get(ctx, f.OEmbedURL+"?"+q.Encode())
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var v struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxBytes)).Decode(&v); err != nil {
		return "", err
	}
	return strings.TrimSpace(v.Title), nil
}

func (f *Fetcher) htmlTitle(ctx context.Context, rawURL string) (string, error) {
	resp, err := f.get(ctx, rawURL)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	return ParseTitle(io.LimitReader(resp.Body, maxBytes))
}

// ParseTitle extracts og:title, falling back to <title>.
func ParseTitle(r io.Reader) (string, error) {
	doc, err := html.Parse(r)
	if err != nil {
		return "", err
	}
	var og, title string
	var walk func(*html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode {
			switch n.Data {
			case "meta":
				var prop, content string
				for _, a := range n.Attr {
					switch a.Key {
					case "property", "name":
						if prop == "" {
							prop = a.Val
						}
					case "content":
						content = a.Val
					}
				}
				if og == "" && prop == "og:title" {
					og = strings.TrimSpace(content)
				}
			case "title":
				if title == "" && n.FirstChild != nil {
					title = strings.Join(strings.Fields(textOf(n)), " ")
				}
			}
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
	}
	walk(doc)
	if og != "" {
		return og, nil
	}
	return title, nil
}

func textOf(n *html.Node) string {
	var sb strings.Builder
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		if c.Type == html.TextNode {
			sb.WriteString(c.Data)
		}
	}
	return sb.String()
}

// Setter records the outcome of a fetch (satisfied by *store.Store).
type Setter interface {
	SetTitle(id int64, title string) error
}

// Job is one URL to resolve.
type Job struct {
	ID  int64
	URL string
}

// Worker runs title fetches off a queue.
type Worker struct {
	F     *Fetcher
	Store Setter
	jobs  chan Job
}

func NewWorker(f *Fetcher, s Setter) *Worker {
	return &Worker{F: f, Store: s, jobs: make(chan Job, 1024)}
}

// Enqueue schedules a fetch without blocking. If the queue is full the job is
// dropped; it is retried at the next startup since title_fetched stays 0.
func (w *Worker) Enqueue(j Job) {
	select {
	case w.jobs <- j:
	default:
		log.Printf("titles: queue full, deferring item %d", j.ID)
	}
}

// Run processes jobs until ctx is cancelled.
func (w *Worker) Run(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case j := <-w.jobs:
			t, err := w.F.Fetch(ctx, j.URL)
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				log.Printf("titles: item %d: %v", j.ID, err)
				t = ""
			}
			if err := w.Store.SetTitle(j.ID, t); err != nil {
				log.Printf("titles: item %d: store: %v", j.ID, err)
			}
		}
	}
}
