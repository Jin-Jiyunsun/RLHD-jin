"""Which foam settings a snapshot had on, across the settings' renames. Old keys linger in RuneLite's settings after a
rename, so a newer key wins over the ones it replaced."""


def foam_on(settings):
    """Short names of the foam settings that were on: foam (all but wakes), or shore, lace and waves before they were
    merged; then wakes and test (test boat wakes)."""
    on = []
    if 'experimentalWaterFoam' in settings:
        if settings['experimentalWaterFoam'] != 'false':
            on.append('foam')
    else:
        shore = settings.get('experimentalShoreFoam', settings.get('experimentalImprovedShoreFoam', 'true'))
        for name, value in (('shore', shore), ('lace', settings.get('experimentalOceanLace', 'true')),
                            ('waves', settings.get('experimentalOceanWaves', 'true'))):
            if value != 'false':
                on.append(name)
    if settings.get('experimentalBoatWakes', 'true') != 'false':
        on.append('wakes')
    if settings.get('experimentalTestBoatWakes') == 'true':
        on.append('test')
    return on
