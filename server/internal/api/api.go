// Package api exposes the HTTP interface.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/vherrmann/linkhole/server/internal/auth"
	"github.com/vherrmann/linkhole/server/internal/store"
	"github.com/vherrmann/linkhole/server/internal/titles"
)

// TitleQueue schedules background title fetches.
type TitleQueue interface{ Enqueue(titles.Job) }

type Server struct {
	store  *store.Store
	titles TitleQueue
}

// New builds the handler. token == "" disables auth. queue may be nil.
func New(s *store.Store, queue TitleQueue, token string) http.Handler {
	srv := &Server{store: s, titles: queue}
	api := http.NewServeMux()
	api.HandleFunc("POST /api/items", srv.add)
	api.HandleFunc("GET /api/items", srv.list)
	api.HandleFunc("GET /api/items/latest", srv.latest)
	api.HandleFunc("GET /api/items/random", srv.random)
	api.HandleFunc("PATCH /api/items/{id}", srv.patch)
	api.HandleFunc("DELETE /api/items/{id}", srv.delete)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "ok\n")
	})
	mux.Handle("/api/", auth.Middleware(token, api))
	return mux
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func (s *Server) fail(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		writeErr(w, http.StatusNotFound, "not found")
	case errors.Is(err, store.ErrInvalidState):
		writeErr(w, http.StatusBadRequest, "invalid state")
	default:
		writeErr(w, http.StatusInternalServerError, "internal error")
	}
}

func validURL(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && (u.Scheme == "http" || u.Scheme == "https") && u.Host != ""
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<16)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid JSON body")
		return false
	}
	return true
}

func (s *Server) add(w http.ResponseWriter, r *http.Request) {
	var in struct {
		URL  string  `json:"url"`
		Note *string `json:"note"`
	}
	if !decode(w, r, &in) {
		return
	}
	in.URL = strings.TrimSpace(in.URL)
	if !validURL(in.URL) {
		writeErr(w, http.StatusBadRequest, "url must be an absolute http(s) URL")
		return
	}
	it, created, err := s.store.Add(in.URL, in.Note)
	if err != nil {
		s.fail(w, err)
		return
	}
	if !created {
		writeJSON(w, http.StatusOK, it)
		return
	}
	if s.titles != nil {
		s.titles.Enqueue(titles.Job{ID: it.ID, URL: it.URL})
	}
	writeJSON(w, http.StatusCreated, it)
}

func (s *Server) list(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	state := q.Get("state")
	if state == "" {
		state = store.Unread
	}
	var since int64
	if v := q.Get("updated_since"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n < 0 {
			writeErr(w, http.StatusBadRequest, "invalid updated_since")
			return
		}
		since = n
	}
	items, err := s.store.List(state, since)
	if err != nil {
		s.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, items)
}

func (s *Server) one(w http.ResponseWriter, it store.Item, err error) {
	if errors.Is(err, store.ErrNotFound) {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if err != nil {
		s.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, it)
}

func (s *Server) latest(w http.ResponseWriter, r *http.Request) {
	it, err := s.store.Latest()
	s.one(w, it, err)
}

func (s *Server) random(w http.ResponseWriter, r *http.Request) {
	it, err := s.store.Random()
	s.one(w, it, err)
}

func (s *Server) patch(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "invalid id")
		return
	}
	var in struct {
		State string `json:"state"`
	}
	if !decode(w, r, &in) {
		return
	}
	it, err := s.store.SetState(id, in.State)
	if err != nil {
		s.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, it)
}

func (s *Server) delete(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "invalid id")
		return
	}
	if err := s.store.Delete(id); err != nil {
		s.fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
