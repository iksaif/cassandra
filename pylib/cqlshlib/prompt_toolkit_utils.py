# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This file contains helpers for prompt_toolkit.

from .lexer import CqlLexer

import os

import prompt_toolkit

from prompt_toolkit import completion
from prompt_toolkit import history
from prompt_toolkit import token
from prompt_toolkit.layout import lexers

readline = None
try:
    import readline
except ImportError:
    pass


class History(history.InMemoryHistory):
    """Loose implementation of a readline compatible history."""

    def __init__(self, filename):
        super(History, self).__init__()
        self.checkpoint = 0
        self.filename = filename

    def load(self):
        if not os.path.exists(self.filename):
            return
        with open(self.filename, 'rb') as f:
            for line in f:
                line = line.decode('utf-8')
                self.append(line.strip())
            self.checkpoint = len(self.strings)

    def save(self):
        with open(self.filename, 'ab') as f:
            for line in self.strings[self.checkpoint:]:
                f.write(line.encode('utf-8') + "\n")
            self.checkpoint = len(self.strings)


class Lexer(lexers.PygmentsLexer):
    """Simple Pygments lexer using CqlLexer."""

    def __init__(self):
        # sql.SqlLexer is a viable alternative.
        super(Lexer, self).__init__(CqlLexer)


class Completer(completion.Completer):
    """A completer that uses cqlrulesets."""

    def __init__(self, connection, cqlruleset, debug_completion=False):
        super(Completer, self).__init__()
        self.connection = connection
        self.cqlruleset = cqlruleset
        self.debug_completion = debug_completion
        if readline:
            delims = readline.get_completer_delims()
            delims.replace("'", "")
            delims += '.'
            readline.set_completer_delims(delims)
        else:
            delims = ' \t\n`~!@#$%^&*()-=+[{]}\|;:",<>/?.'
        self.delims = delims

    def get_completions(self, document, complete_event):
        """Returns possible completions given a document."""

        text = document.get_word_before_cursor(WORD=True)
        stuff_to_complete = document.text_before_cursor
        if len(text):
            stuff_to_complete = stuff_to_complete[:-len(text)]

        for i in range(len(text)-1, 0, -1):
            if text[i] in self.delims:
                stuff_to_complete += text[:i+1]
                text = text[i+1:]
                break

        res = self.cqlruleset.cql_complete(
            stuff_to_complete, text,
            cassandra_conn=self.connection,
            debug=self.debug_completion,
            startsymbol='cqlshCommand')
        for a in res:
            yield completion.Completion(a, -len(text))


def get_bottom_toolbar_tokens(connection):
    def _get_bottom_toolbar_tokens(cli):
        cluster = connection.conn
        session = connection.session
        text = ""
        if connection.username:
            text += "%@" % connection.username
        text += '%s:%d - %s'% (
            connection.hostname, connection.port,
            connection.get_cluster_name()
        )
        if cluster.cql_version:
            text += '(CQL: %s)' % (cluster.cql_version)

        hosts_total = 0
        hosts_up = 0
        for host in cluster.metadata.all_hosts():
            hosts_total += 1
            if host.is_up:
                hosts_up += 1
        text += " - Hosts: %d/%d" % (hosts_up, hosts_total)
        # TODO: Add current consitency level ?
        return [(token.Token.Toolbar, text)]
    return _get_bottom_toolbar_tokens
