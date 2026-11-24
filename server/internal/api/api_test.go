package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/vherrmann/linkhole/server/internal/store"
	"github.com/vherrmann/linkhole/server/internal/titles"
)

type recorder struct{ jobs []titles.Job }

func (r *recorder) Enqueue(j titles.Job) { r.jobs = append(r.jobs, j) }

type env struct {
	t     *testing.T
	h     http.Handler
	queue *recorder
}

func newEnv(t *testing.T, token string) *env {
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	q := &recorder{}
	return &env{t, New(st, q, token), q}
}

func (e *env) do(method, path, body, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, req)
	return w
}

func (e *env) item(w *httptest.ResponseRecorder) store.Item {
	var it store.Item
	if err := json.Unmarshal(w.Body.Bytes(), &it); err != nil {
		e.t.Fatalf("decode %q: %v", w.Body.String(), err)
	}
	return it
}

func TestAuth(t *testing.T) {
	e := newEnv(t, "secret")
	for _, tc := range []struct {
		token string
		want  int
	}{{"", 401}, {"wrong", 401}, {"secre", 401}, {"secret", 200}} {
		if got := e.do("GET", "/api/items", "", tc.token).Code; got != tc.want {
			t.Errorf("token %q: %d, want %d", tc.token, got, tc.want)
		}
	}
	if got := e.do("GET", "/healthz", "", "").Code; got != 200 {
		t.Errorf("healthz: %d", got)
	}
	// Basic scheme must not pass.
	req := httptest.NewRequest("GET", "/api/items", nil)
	req.Header.Set("Authorization", "Basic secret")
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, req)
	if w.Code != 401 {
		t.Errorf("basic scheme: %d", w.Code)
	}
}

func TestNoAuthMode(t *testing.T) {
	e := newEnv(t, "")
	if got := e.do("GET", "/api/items", "", "").Code; got != 200 {
		t.Fatal(got)
	}
}

func TestAddDedupeAndValidation(t *testing.T) {
	e := newEnv(t, "")
	w := e.do("POST", "/api/items", `{"url":"https://x.example/a","note":"hi"}`, "")
	if w.Code != 201 {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	first := e.item(w)
	if first.Note == nil || *first.Note != "hi" || first.State != "unread" {
		t.Fatalf("%+v", first)
	}
	if len(e.queue.jobs) != 1 {
		t.Fatalf("jobs: %v", e.queue.jobs)
	}
	w = e.do("POST", "/api/items", `{"url":"https://x.example/a"}`, "")
	if w.Code != 200 || e.item(w).ID != first.ID {
		t.Fatalf("dup: %d %s", w.Code, w.Body)
	}
	if len(e.queue.jobs) != 1 {
		t.Fatal("duplicate must not enqueue a title fetch")
	}
	for _, body := range []string{`{}`, `{"url":"ftp://x/"}`, `{"url":"javascript:alert(1)"}`, `{"url":"/rel"}`, `nope`} {
		if got := e.do("POST", "/api/items", body, "").Code; got != 400 {
			t.Errorf("%s: %d", body, got)
		}
	}
}

func TestLatestRandomPatchFlow(t *testing.T) {
	e := newEnv(t, "")
	if e.do("GET", "/api/items/latest", "", "").Code != 204 || e.do("GET", "/api/items/random", "", "").Code != 204 {
		t.Fatal("empty should be 204")
	}
	a := e.item(e.do("POST", "/api/items", `{"url":"https://a/"}`, ""))
	b := e.item(e.do("POST", "/api/items", `{"url":"https://b/"}`, ""))

	for i := 0; i < 3; i++ {
		if got := e.item(e.do("GET", "/api/items/latest", "", "")); got.ID != b.ID {
			t.Fatalf("latest = %d", got.ID)
		}
	}
	if got := e.item(e.do("GET", "/api/items/random", "", "")); got.ID != a.ID {
		t.Fatalf("random should exclude latest, got %d", got.ID)
	}

	p := e.do("PATCH", "/api/items/"+itoa(b.ID), `{"state":"read"}`, "")
	if p.Code != 200 || e.item(p).State != "read" {
		t.Fatalf("%d %s", p.Code, p.Body)
	}
	if got := e.item(e.do("GET", "/api/items/latest", "", "")); got.ID != a.ID {
		t.Fatalf("latest after read = %d", got.ID)
	}
	if got := len(decodeList(t, e.do("GET", "/api/items?state=all", "", ""))); got != 2 {
		t.Fatalf("all: %d", got)
	}
	if got := len(decodeList(t, e.do("GET", "/api/items", "", ""))); got != 1 {
		t.Fatalf("default unread: %d", got)
	}
	if e.do("PATCH", "/api/items/"+itoa(b.ID), `{"state":"unread"}`, "").Code != 200 {
		t.Fatal("reset to unread")
	}
	if got := e.item(e.do("GET", "/api/items/latest", "", "")); got.ID != b.ID {
		t.Fatal("latest after reset")
	}

	for _, tc := range []struct {
		method, path, body string
		want               int
	}{
		{"PATCH", "/api/items/999", `{"state":"read"}`, 404},
		{"PATCH", "/api/items/" + itoa(a.ID), `{"state":"bogus"}`, 400},
		{"PATCH", "/api/items/abc", `{"state":"read"}`, 400},
		{"GET", "/api/items?state=bogus", "", 400},
		{"GET", "/api/items?updated_since=x", "", 400},
		{"DELETE", "/api/items/999", "", 404},
		{"DELETE", "/api/items/" + itoa(a.ID), "", 204},
		{"DELETE", "/api/items/" + itoa(a.ID), "", 404},
	} {
		if got := e.do(tc.method, tc.path, tc.body, "").Code; got != tc.want {
			t.Errorf("%s %s: %d, want %d", tc.method, tc.path, got, tc.want)
		}
	}
}

func decodeList(t *testing.T, w *httptest.ResponseRecorder) []store.Item {
	t.Helper()
	var l []store.Item
	if err := json.Unmarshal(w.Body.Bytes(), &l); err != nil {
		t.Fatalf("%q: %v", w.Body, err)
	}
	return l
}

func itoa(n int64) string { b, _ := json.Marshal(n); return string(b) }
