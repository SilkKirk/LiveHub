import yaml

with open('.github/workflows/ci.yml', encoding='utf-8') as handle:
    doc = yaml.safe_load(handle)

print('workflow:', doc['name'])
for job, spec in doc['jobs'].items():
    print('- %s  name=%r  needs=%r  timeout=%r' % (
        job, spec.get('name'), spec.get('needs'), spec.get('timeout-minutes')))
    for step in spec['steps']:
        label = step.get('name') or step.get('uses')
        print('     *', label)
