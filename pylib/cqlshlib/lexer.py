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

import re

from cassandra.metadata import cql_keywords

from pygments.lexer import RegexLexer
from pygments.token import *


# From https://gist.github.com/al3xandru/9383312, should be sent to pygments.
class CqlLexer(RegexLexer):
    """
    Lexer for Cassandra Query Language.
    Spec available `here<https://cassandra.apache.org/doc/cql3/CQL.html>`_.
    """

    name = 'CQL'
    aliases = ['cql']
    filenames = ['*.cql']
    mimetypes = ['text/x-sql']

    flags = re.IGNORECASE
    tokens = {
        'root': [
            (r'\s+', Text),
            (r'--.*?\n', Comment.Single),
            (r'//.*\n', Comment.Single),
            (r'/\*', Comment.Multiline, 'multiline-comments'),
            (r'(%s)\b' % '|'.join(cql_keywords), Keyword),
            (r'(TRUE|FALSE)\b', Literal),
            (r'(NAN|INFINITY)\b', Number.Float),
            (r'[\*+<>=-]', Operator),
            (r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}', Number.Hex),
            (r'0[xX][0-9a-fA-F]*\b', Number.Hex),
            (r'[0-9]+(\.[0-9]*)?([eE][+-]?[0-9+])?\b', Number.Float),
            (r'[0-9]+\b', Number.Integer),
            (r"'(''|[^'])*'", String.Symbol),
            (r'"(""|[^"])*"', Name),
            (r'[a-zA-Z][a-zA-Z0-9_]*', Name),
            (r'[;:()\[\],\.\{\}\<\>]', Punctuation)
        ],
        'multiline-comments': [
            (r'/\*', Comment.Multiline, 'multiline-comments'),
            (r'\*/', Comment.Multiline, '#pop'),
            (r'[^/\*]+', Comment.Multiline),
            (r'[/*]', Comment.Multiline)
        ]
    }
