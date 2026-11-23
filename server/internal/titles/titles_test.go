package titles

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestParseTitle(t *testing.T) {
	for name, tc := range map[string]struct{ in, want string }{
		"title": {`<html><head><title>  Hello
  World </title></head></html>`, "Hello World"},
		"og wins":     {`<head><title>T</title><meta property="og:title" content="OG"></head>`, "OG"},
		"og name":     {`<head><meta name="og:title" content="OG2"></head>`, "OG2"},
		"entities":    {`<title>A &amp; B</title>`, "A & B"},
		"none":        {`<p>nothing</p>`, ""},
		"broken html": {`<title>Cut off`, "Cut off"},
	} {
		got, err := ParseTitle(strings.NewReader(tc.in))
		if err != nil || got != tc.want {
			t.Errorf("%s: got %q, %v; want %q", name, got, err, tc.want)
		}
	}
}

func fetcher(oembed string) *Fetcher {
	return &Fetcher{Client: &http.Client{Timeout: 2 * time.Second}, OEmbedURL: oembed}
}

func TestFetchHTML(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/404" {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte("<title>Page</title>"))
	}))
	defer ts.Close()
	f := fetcher("")
	if got, err := f.Fetch(context.Background(), ts.URL+"/x"); err != nil || got != "Page" {
		t.Fatalf("got %q %v", got, err)
	}
	if _, err := f.Fetch(context.Background(), ts.URL+"/404"); err == nil {
		t.Fatal("expected error on 404")
	}
	if _, err := f.Fetch(context.Background(), "file:///etc/passwd"); err == nil {
		t.Fatal("expected error on non-http scheme")
	}
}

func TestFetchBodyCap(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(strings.Repeat("x", maxBytes+10)))
		w.Write([]byte("<title>late</title>"))
	}))
	defer ts.Close()
	if got, _ := fetcher("").Fetch(context.Background(), ts.URL); got != "" {
		t.Fatalf("title beyond cap should be ignored, got %q", got)
	}
}

func TestFetchYouTubeOEmbed(t *testing.T) {
	oe := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("url") != "https://www.youtube.com/watch?v=abc" {
			http.Error(w, "bad", 400)
			return
		}
		w.Write([]byte(`{"title":"Video Title"}`))
	}))
	defer oe.Close()
	got, err := fetcher(oe.URL).Fetch(context.Background(), "https://www.youtube.com/watch?v=abc")
	if err != nil || got != "Video Title" {
		t.Fatalf("got %q %v", got, err)
	}
}

type fakeSetter struct{ ch chan string }

func (f fakeSetter) SetTitle(id int64, title string) error { f.ch <- title; return nil }

func TestWorkerMarksFetchedOnFailure(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "no", 500)
	}))
	defer ts.Close()
	fs := fakeSetter{make(chan string, 1)}
	w := NewWorker(fetcher(""), fs)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go w.Run(ctx)
	w.Enqueue(Job{ID: 1, URL: ts.URL})
	select {
	case got := <-fs.ch:
		if got != "" {
			t.Fatalf("got %q", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("worker did not record result")
	}
}
