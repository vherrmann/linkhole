;;; the-next-thing.el --- Client for the-next-thing link queue  -*- lexical-binding: t; -*-

;; Version: 0.1.0
;; Package-Requires: ((emacs "28.1"))
;; Keywords: convenience, hypermedia

;;; Commentary:

;; Push links to a the-next-thing server while going through an inbox, and
;; list/manage the queue.  Uses only built-in libraries.
;;
;;   M-x the-next-thing-add            prompt for a URL (and note)
;;   M-x the-next-thing-add-at-point   org link / URL at point
;;   M-x the-next-thing-add-region     every URL in the region
;;   M-x the-next-thing-list           browse and manage the queue
;;
;; Configure `the-next-thing-server-url'.  The bearer token is looked up with
;; `auth-source' (host = server host); `the-next-thing-token' is the fallback.

;;; Code:

(require 'auth-source)
(require 'cl-lib)
(require 'json)
(require 'org-element)
(require 'subr-x)
(require 'tabulated-list)
(require 'thingatpt)
(require 'url)
(require 'url-http)
(require 'url-parse)

(defgroup the-next-thing nil
  "Client for the-next-thing link queue."
  :group 'convenience
  :prefix "the-next-thing-")

(defcustom the-next-thing-server-url "http://127.0.0.1:8787"
  "Base URL of the server, without trailing slash."
  :type 'string)

(defcustom the-next-thing-token nil
  "Bearer token, used when `auth-source' has no entry for the server host."
  :type '(choice (const nil) string))

(defvar url-http-end-of-headers)
(defvar url-http-response-status)

;;;; HTTP

(defun the-next-thing--token ()
  "Return the bearer token or signal a `user-error'."
  (let* ((host (url-host (url-generic-parse-url the-next-thing-server-url)))
         (found (car (auth-source-search :host host :max 1)))
         (secret (and found (plist-get found :secret))))
    (when (functionp secret) (setq secret (funcall secret)))
    (or (and (stringp secret) (not (string-empty-p secret)) secret)
        the-next-thing-token
        (user-error "No the-next-thing token: set `the-next-thing-token' or add an auth-source entry for %s" host))))

(defun the-next-thing--request (method path data callback)
  "Send METHOD to PATH with alist DATA as JSON; call CALLBACK with (CODE BODY).
BODY is the parsed JSON (alists, nil for null/absent) or nil.  CODE is nil on
a transport error, in which case BODY is the error description."
  (let ((url-request-method method)
        (url-request-extra-headers
         `(("Authorization" . ,(concat "Bearer " (the-next-thing--token)))
           ("Content-Type" . "application/json")))
        (url-request-data (and data (json-serialize data)))
        (url-show-status nil))
    (url-retrieve
     (concat (string-remove-suffix "/" the-next-thing-server-url) path)
     (lambda (status)
       (let ((buf (current-buffer)))
         (unwind-protect
             (if (not (integerp url-http-response-status))
                 (funcall callback nil (plist-get status :error))
               ;; 4xx/5xx set :error too, but the response is still usable.
               (let ((code url-http-response-status)
                     body)
                 (goto-char (1+ url-http-end-of-headers))
                 (unless (eobp)
                   (setq body (ignore-errors
                                (json-parse-buffer :object-type 'alist
                                                   :null-object nil
                                                   :false-object nil))))
                 (funcall callback code body)))
           (kill-buffer buf))))
     nil t)))

(defun the-next-thing--error-message (code body)
  "Describe a failed response for the user."
  (cond ((null code) (format "request failed: %S" body))
        ((= code 401) "unauthorized (check token)")
        (t (format "HTTP %d%s" code
                   (if-let* ((e (and (listp body) (alist-get 'error body))))
                       (concat ": " e) "")))))

;;;; URL extraction

(defconst the-next-thing--url-regexp
  "https?://[^] \t\n\r<>\"[]+"
  "Regexp matching a bare URL (stops at `]' so org links work).")

(defun the-next-thing--clean-url (url)
  "Strip trailing punctuation and unbalanced `)' from URL."
  (let ((case-fold-search nil))
    (while (or (string-match-p "[.,;:!?'\"]\\'" url)
               (and (string-suffix-p ")" url)
                    (> (cl-count ?\) url) (cl-count ?\( url))))
      (setq url (substring url 0 -1))))
  url)

(defun the-next-thing--extract-urls (string)
  "Return the distinct http(s) URLs in STRING, in order of appearance."
  (let ((pos 0) urls)
    (while (string-match the-next-thing--url-regexp string pos)
      (setq pos (match-end 0))
      (let ((u (the-next-thing--clean-url (match-string 0 string))))
        (when (string-match-p "\\`https?://[^/]+" u)
          (cl-pushnew u urls :test #'equal))))
    (nreverse urls)))

(defun the-next-thing--url-at-point ()
  "Return the URL at point (org link or plain), or nil."
  (or (when (derived-mode-p 'org-mode)
        (let ((ctx (org-element-context)))
          (when (eq (org-element-type ctx) 'link)
            (let ((raw (org-element-property :raw-link ctx)))
              (car (the-next-thing--extract-urls (or raw "")))))))
      (let ((u (thing-at-point 'url t)))
        (and u (car (the-next-thing--extract-urls u))))))

;;;; Adding

(defun the-next-thing--add (url note callback)
  "POST URL (with optional NOTE).
CALLBACK gets `added', `duplicate' or an error string."
  (the-next-thing--request
   "POST" "/api/items"
   `((url . ,url) ,@(and note (not (string-empty-p note)) `((note . ,note))))
   (lambda (code body)
     (funcall callback
              (cond ((eql code 201) 'added)
                    ((eql code 200) 'duplicate)
                    (t (the-next-thing--error-message code body)))))))

(defun the-next-thing--report-single (url result)
  (pcase result
    ('added (message "the-next-thing: added %s" url))
    ('duplicate (message "the-next-thing: already queued: %s" url))
    (err (message "the-next-thing: failed to add %s: %s" url err))))

;;;###autoload
(defun the-next-thing-add (url &optional note)
  "Add URL to the queue, with optional NOTE.
Interactively, prompt for both; the URL defaults to the one at point or the
latest kill."
  (interactive
   (let* ((default (or (ignore-errors (the-next-thing--url-at-point))
                       (car (the-next-thing--extract-urls
                             (or (ignore-errors (current-kill 0 t)) "")))))
          (url (read-string (format-prompt "URL" default) nil nil default))
          (note (read-string "Note (optional): ")))
     (list url note)))
  (unless (the-next-thing--extract-urls url)
    (user-error "Not an http(s) URL: %s" url))
  (the-next-thing--add url note
                       (lambda (r) (the-next-thing--report-single url r))))

;;;###autoload
(defun the-next-thing-add-at-point ()
  "Add the org link or URL at point."
  (interactive)
  (let ((url (or (the-next-thing--url-at-point)
                 (user-error "No URL at point"))))
    (the-next-thing--add url nil
                         (lambda (r) (the-next-thing--report-single url r)))))

;;;###autoload
(defun the-next-thing-add-region (beg end)
  "Add every URL (including org links) between BEG and END, in order."
  (interactive "r")
  (let ((urls (the-next-thing--extract-urls (buffer-substring-no-properties beg end)))
        (added 0) (dups 0) (errors nil))
    (when (null urls) (user-error "No URLs in region"))
    ;; Sequential, so the last URL in the text ends up newest in the queue.
    (cl-labels
        ((next ()
           (if (null urls)
               (message "the-next-thing: %d added, %d already queued%s"
                        added dups
                        (if errors
                            (format ", %d failed (%s)" (length errors) (cdar errors))
                          ""))
             (let ((u (pop urls)))
               (the-next-thing--add
                u nil
                (lambda (r)
                  (pcase r
                    ('added (cl-incf added))
                    ('duplicate (cl-incf dups))
                    (err (push (cons u err) errors)))
                  (next)))))))
      (next))))

;;;; List buffer

(defconst the-next-thing--filters '("unread" "read" "uninteresting" "all"))

(defvar-local the-next-thing--filter "unread")

(defvar-keymap the-next-thing-list-mode-map
  :parent tabulated-list-mode-map
  "RET" #'the-next-thing-list-browse
  "r" #'the-next-thing-list-mark-read
  "x" #'the-next-thing-list-mark-uninteresting
  "u" #'the-next-thing-list-mark-unread
  "f" #'the-next-thing-list-cycle-filter
  "g" #'the-next-thing-list-refresh
  "d" #'the-next-thing-list-delete)

(define-derived-mode the-next-thing-list-mode tabulated-list-mode "TheNextThing"
  "Major mode for browsing the-next-thing queue."
  (setq tabulated-list-format
        [("State" 13 t) ("Added" 16 t) ("Title" 50 t) ("URL" 0 t)])
  (setq tabulated-list-padding 1)
  (tabulated-list-init-header))

(defun the-next-thing--format-time (ms)
  (format-time-string "%Y-%m-%d %H:%M" (/ ms 1000)))

(defun the-next-thing--entry (item)
  "Convert ITEM (alist) to a `tabulated-list-entries' element."
  (let-alist item
    (list .id (vector .state
                      (the-next-thing--format-time .created_at)
                      (or .title "—")
                      .url))))

(defun the-next-thing--update-mode-name ()
  (setq mode-name (format "TheNextThing[%s]" the-next-thing--filter))
  (force-mode-line-update))

(defun the-next-thing-list-refresh ()
  "Reload the list from the server."
  (interactive nil the-next-thing-list-mode)
  (let ((buf (current-buffer))
        (filter the-next-thing--filter))
    (the-next-thing--update-mode-name)
    (the-next-thing--request
     "GET" (format "/api/items?state=%s" filter) nil
     (lambda (code body)
       (cond
        ((not (eql code 200))
         (message "the-next-thing: %s" (the-next-thing--error-message code body)))
        ((buffer-live-p buf)
         (with-current-buffer buf
           (setq tabulated-list-entries
                 (mapcar #'the-next-thing--entry (append body nil)))
           (tabulated-list-print t))))))))

;;;###autoload
(defun the-next-thing-list ()
  "Show the queue in the `*the-next-thing*' buffer."
  (interactive)
  (with-current-buffer (get-buffer-create "*the-next-thing*")
    (unless (derived-mode-p 'the-next-thing-list-mode)
      (the-next-thing-list-mode))
    (pop-to-buffer (current-buffer))
    (the-next-thing-list-refresh)))

(defun the-next-thing-list-cycle-filter ()
  "Cycle the state filter: unread, read, uninteresting, all."
  (interactive nil the-next-thing-list-mode)
  (setq the-next-thing--filter
        (or (cadr (member the-next-thing--filter the-next-thing--filters))
            (car the-next-thing--filters)))
  (the-next-thing-list-refresh))

(defun the-next-thing--current-url ()
  (aref (tabulated-list-get-entry) 3))

(defun the-next-thing-list-browse ()
  "Open the URL at point in the browser."
  (interactive nil the-next-thing-list-mode)
  (when (tabulated-list-get-entry)
    (browse-url (the-next-thing--current-url))))

(defun the-next-thing--set-state (state)
  (let ((id (or (tabulated-list-get-id) (user-error "No item at point")))
        (buf (current-buffer)))
    (the-next-thing--request
     "PATCH" (format "/api/items/%d" id) `((state . ,state))
     (lambda (code body)
       (if (eql code 200)
           (when (buffer-live-p buf)
             (with-current-buffer buf (the-next-thing-list-refresh)))
         (message "the-next-thing: %s" (the-next-thing--error-message code body)))))))

(defun the-next-thing-list-mark-read ()
  (interactive nil the-next-thing-list-mode)
  (the-next-thing--set-state "read"))

(defun the-next-thing-list-mark-uninteresting ()
  (interactive nil the-next-thing-list-mode)
  (the-next-thing--set-state "uninteresting"))

(defun the-next-thing-list-mark-unread ()
  (interactive nil the-next-thing-list-mode)
  (the-next-thing--set-state "unread"))

(defun the-next-thing-list-delete ()
  "Delete the item at point after confirmation."
  (interactive nil the-next-thing-list-mode)
  (let ((id (or (tabulated-list-get-id) (user-error "No item at point")))
        (buf (current-buffer)))
    (when (y-or-n-p (format "Delete %s? " (the-next-thing--current-url)))
      (the-next-thing--request
       "DELETE" (format "/api/items/%d" id) nil
       (lambda (code body)
         (if (eql code 204)
             (when (buffer-live-p buf)
               (with-current-buffer buf (the-next-thing-list-refresh)))
           (message "the-next-thing: %s" (the-next-thing--error-message code body))))))))

(provide 'the-next-thing)
;;; the-next-thing.el ends here
