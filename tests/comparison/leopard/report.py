# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from __future__ import absolute_import, division, print_function
import pickle
import re
import os
import job
from collections import defaultdict

class Report(object):
  '''Contains information about a completed job, such as the number of crashes and stack
  traces from every crash. The report is usually displayed on a web page.
  '''
  def __init__(self, job_id):
    self.num_queries = 0
    self.run_time = 0
    self.run_date = 0
    self.job_name = ''
    self.num_crashes = 0
    self.num_row_count_mismatch = 0
    self.num_mismatch = 0
    self.job_id = job_id
    self.git_hash = ''
    self.grouped_results = None
    self.parent_job_name = ''
    self.num_queries_returned_correct_data = 0
    self.get_results()

  @property
  def run_time_str(self):
    '''Return the running time of the job as a string in human readable format.'''
    m, s = divmod(self.run_time, 60)
    h, m = divmod(m, 60)
    return '{0:02d}:{1:02d}:{2:02d}'.format(int(h), int(m), int(s))

  def classify_error(self, error):
    d = {
      r'LINE \d+:': 'Postgres_error',
      r'Permission denied': 'permission_denied',
      r'^AnalysisException': 'AnalysisException',
      r'^Column \d+ in row \d+ does not match': 'mismatch',
      r'^Could not connect': 'could_not_connect',
      r'^IllegalStateException': 'IllegalStateException',
      r'^Invalid query handle: ': 'invalid_query_handle',
      r'^Invalid or unknown query handle: ': 'invalid_query_handle',
      r'^Known issue:': 'known_issue',
      r'^Operation is in ERROR_STATE': 'error_state',
      r'^Query timed out after \d+ seconds': 'timeout',
      r'^Row counts do not match': 'row_counts',
      r'^Too much data': 'too_much_data',
      r'^Unknown expr node type: \d+': 'unkown_node',
      r'^Year is out of valid range': 'year_range',
      r'^[A-Za-z]+ out of range': 'out_of_range',
      r'^division by zero': 'division_by_zero'}

    for r in d:
      if re.search(r, error):
        return d[r]
    return 'unrecognized'

  def group_queries(self, all_queries, group_func):
    '''General function that returns a dictionary with keys that are generated by
    group_func. all_queries is a list of queries.
    group_func should take query as a parameter and return a string containing an
    interesting property of the query which will be used as key in the dictionary.
    '''
    grouped_queries = defaultdict(list)
    for query in all_queries:
      grouped_queries[group_func(query)].append(query)
    return grouped_queries

  def __str__(self):
    '''TODO: Render report as text.
    '''
    return ''

  def get_first_impala_frame(self, query_result):
    '''Extracts the first impala frame in the stack trace.
    '''
    stack = query_result['formatted_stack']
    if stack:
      for line in stack.split('\n'):
        match = re.search(r'(impala::.*) \(', line)
        if match:
          return match.group(1)
    else:
      return None

  def _format_stack(self, stack):
    '''Cleans up the stack trace.
    '''

    def clean_frame(frame):
      #remove memory address from each frame
      reg = re.match(r'#\d+ *0x[0123456789abcdef]* in (.*)', frame)
      if reg: return reg.group(1)
      # this is for matching lines like "#7  SLL_Next (this=0x9046780, src=0x90467c8...
      reg = re.match(r'#\d+ *(\S.*)', frame)
      if reg: return reg.group(1)
      return frame

    def stack_gen():
      '''Generator that yields impala stack trace lines line by line.
      '''
      if stack:
        active = False
        for line in stack.split('\n'):
          if active or line.startswith('#0'):
            active = True
            yield line

    return '\n'.join(clean_frame(l) for l in stack_gen())

  def get_results(self):
    '''Analyses the completed job and extracts important results into self. This method
    should be called as soon as the object is created.
    '''
    from controller import PATH_TO_FINISHED_JOBS

    def group_outer_func(query):
      if 'stack' in query:
        return 'stack'
      return self.classify_error(query['error'])

    def stack_group_func(query):
      return self.get_first_impala_frame(query['stack'])

    with open(os.path.join(PATH_TO_FINISHED_JOBS, self.job_id)) as f:
      job = pickle.load(f)
      self.grouped_results = self.group_queries(job.result_list, group_outer_func)

    # Format the stack for queries that have a stack
    for query in self.grouped_results['stack']:
      query['formatted_stack'] = self._format_stack(query['stack'])

    self.num_crashes = len(self.grouped_results['stack'])
    self.num_row_count_mismatch = len(self.grouped_results['row_counts'])
    self.num_mismatch = len(self.grouped_results['mismatch'])

    self.grouped_stacks = self.group_queries(
        self.grouped_results['stack'], self.get_first_impala_frame)

    self.run_time = job.stop_time - job.start_time
    self.run_date = job.start_time
    self.job_name = job.job_name
    self.git_hash = job.git_hash
    self.num_queries_executed = job.num_queries_executed
    self.num_queries_returned_correct_data = job.num_queries_returned_correct_data
    if job.parent_job:
      with open(os.path.join(PATH_TO_FINISHED_JOBS, job.parent_job)) as f:
        parent_job = pickle.load(f)
        self.parent_job_name = parent_job.job_name

  def save_pickle(self):
    from controller import PATH_TO_REPORTS
    with open(os.path.join(PATH_TO_REPORTS, self.job_id), 'w') as f:
      pickle.dump(self, f)
